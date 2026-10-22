package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.retrieval.index.RetrievalIndexManager;
import com.noteweave.retrieval.index.RetrievalProjectionWriter;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.Projection;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionStatus;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionService.BatchEmbeddings;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.source.SourceChunkContextEnhancer;
import com.noteweave.source.SourceChunkContextProperties;
import com.noteweave.source.SourceTagCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class SourceEmbeddingBatchProjectionTest {
    private JdbcTemplate jdbc;
    private NoteWeaveProperties properties;
    private RetrievalProjectionWriter writer;
    private SourceChunkContextEnhancer enhancer;
    private SourceRetrievalProjectionService service;
    private RecordingEmbeddings embeddings;
    private RetrievalIndexManager manager;
    private RetrievalProjectionRepository repository;

    @BeforeEach
    void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:batch-projection-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table workspace(id varchar(36) primary key)");
        jdbc.update("insert into workspace(id) values ('workspace')");
        jdbc.execute("""
                create table source(id varchar(36), workspace_id varchar(36), title varchar(300), source_type varchar(40),
                    summary longtext, tags_json longtext, metadata_json longtext, status varchar(40))
                """);
        jdbc.execute("create table source_snapshot(id varchar(36), source_id varchar(36), sha256 varchar(80), parse_status varchar(40))");
        jdbc.execute("""
                create table source_chunk(id varchar(36), workspace_id varchar(36), source_id varchar(36),
                    source_snapshot_id varchar(36), chunk_no int, heading varchar(300), content longtext,
                    structure_metadata_json longtext)
                """);
        jdbc.execute("create table source_window(source_chunk_id varchar(36))");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V146__source_chunk_context.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V147__source_embedding_cache.sql")).execute(ds);
        jdbc.update("insert into source values ('source','workspace','资料标题','USER_UPLOAD','','[]','{}','PROCESSING')");
        jdbc.update("insert into source_snapshot values ('snapshot','source',?,'PARSED')", "a".repeat(64));
        properties = mock(NoteWeaveProperties.class);
        when(properties.document()).thenReturn(new NoteWeaveProperties.Document(800, 100));
        configureInputLimit(1000);
        embeddings = new RecordingEmbeddings();
        writer = mock(RetrievalProjectionWriter.class);
        manager = mock(RetrievalIndexManager.class);
        when(manager.resolveWriteIndex(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        repository = mock(RetrievalProjectionRepository.class);
        when(repository.createPending(any())).thenAnswer(call -> {
            Projection projection = mock(Projection.class);
            when(projection.id()).thenReturn("projection");
            when(projection.status()).thenReturn(ProjectionStatus.PENDING);
            return projection;
        });
        when(repository.prepareForProjection(any())).thenAnswer(call -> call.getArgument(0));
        enhancer = mock(SourceChunkContextEnhancer.class);
        when(enhancer.currentContextPromptVersion()).thenReturn("context-v1");
        when(enhancer.enhance(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(call -> {
                    List<SourceChunkContextEnhancer.ChunkInput> chunks = call.getArgument(5);
                    return new SourceChunkContextEnhancer.EnhancementResult(
                            chunks.stream().map(SourceChunkContextEnhancer.ChunkInput::chunkId).toList(),
                            chunks.stream().map(ignored -> "本节介绍缓存一致性").toList(),
                            chunks.stream().map(ignored -> "").toList(), 0, enhancer.currentContextPromptVersion());
                });
        service = new SourceRetrievalProjectionService(jdbc, embeddings, repository, manager, writer, properties,
                new SourceTagCodec(new ObjectMapper()), new SourceEmbeddingCacheRepository(jdbc, new ObjectMapper()), enhancer);
    }

    @Test
    void largeSnapshotComputesAtMost64ChunksAndCarriesNoteOnlyInLastBatch() {
        seedChunks(130);
        BatchEmbeddings first = compute(0);
        BatchEmbeddings second = compute(1);
        BatchEmbeddings last = compute(2);

        assertThat(first.chunkIds()).hasSize(64);
        assertThat(second.chunkIds()).hasSize(64);
        assertThat(last.chunkIds()).hasSize(2);
        assertThat(first.noteVector()).isEmpty();
        assertThat(second.noteVector()).isEmpty();
        assertThat(last.noteVector()).hasSize(2);
        assertThat(embeddings.requests).extracting(List::size).containsExactly(64, 64, 2, 1);
        assertThat(service.currentEmbeddingRequestBudget(64, true)).isEqualTo(9);
        assertThat(service.currentChunkerVersion("workspace", "source", "snapshot"))
                .isEqualTo("document-chunker-legacy-unknown");
    }

    @Test
    void historicalChunkerVersionSurvivesConfigurationChangesAndDoubleEncodedMetadata() throws Exception {
        seedChunks(1);
        String metadata = "{\"chunker_version\":\"document-chunker-v3:800:100\"}";
        jdbc.update("update source set metadata_json = ?", metadata);
        BatchEmbeddings original = compute(0);
        when(properties.document()).thenReturn(new NoteWeaveProperties.Document(1600, 200));
        BatchEmbeddings changedConfiguration = compute(0);

        assertThat(original.chunkerVersion()).isEqualTo("document-chunker-v3:800:100");
        assertThat(changedConfiguration.chunkerVersion()).isEqualTo(original.chunkerVersion());
        assertThat(changedConfiguration.embeddingVersion()).isNotEqualTo(original.embeddingVersion());
        jdbc.update("update source set metadata_json = ?", new ObjectMapper().writeValueAsString(metadata));
        assertThat(service.currentChunkerVersion("workspace", "source", "snapshot"))
                .isEqualTo("document-chunker-v3:800:100");
    }

    @Test
    void missingOrInvalidHistoricalChunkerVersionNeverClaimsCurrentConfiguration() {
        seedChunks(1);
        for (String invalid : List.of("{}", "{\"chunker_version\":\"document-chunker-v3:32:8\"}",
                "{\"chunker_version\":\"document-chunker-v3:800:201\"}",
                "{\"chunker_version\":\"document-chunker-v3:9999999999:0\"}", "not-json")) {
            jdbc.update("update source set metadata_json = ?", invalid);
            assertThat(service.currentChunkerVersion("workspace", "source", "snapshot"))
                    .isEqualTo("document-chunker-legacy-unknown");
        }
    }

    @Test
    void longLegalModelNamesKeepBoundedDistinctVersionsAndStoreTheirFullIdentity() {
        seedChunks(1);
        String firstModel = "m".repeat(119) + "a";
        String secondModel = "m".repeat(119) + "b";
        when(properties.embedding()).thenReturn(new NoteWeaveProperties.Embedding(true, "http://localhost:18105/v1",
                firstModel, "", 2, 32, 10, 20, 1000, 3));
        embeddings.model = firstModel;
        BatchEmbeddings first = compute(0);
        when(properties.embedding()).thenReturn(new NoteWeaveProperties.Embedding(true, "http://localhost:18105/v1",
                secondModel, "", 2, 32, 10, 20, 1000, 3));
        embeddings.model = secondModel;
        BatchEmbeddings second = compute(0);

        assertThat(first.embeddingVersion().length()).isLessThanOrEqualTo(128);
        assertThat(second.embeddingVersion().length()).isLessThanOrEqualTo(128);
        assertThat(second.embeddingVersion()).isNotEqualTo(first.embeddingVersion());
        assertThat(first.model()).isEqualTo(firstModel);
        assertThat(second.model()).isEqualTo(secondModel);
        assertThat(jdbc.queryForList("select embedding_model from source_embedding_cache order by embedding_model", String.class))
                .containsExactly(firstModel, secondModel);
    }

    @Test
    void budgetPathChargesMissesAndNoteButNotCachedQa() {
        seedChunks(2);
        var permits = new java.util.concurrent.atomic.AtomicInteger();
        service.computeEmbeddingBatch("workspace", "source", "snapshot", 0, ignored -> {}, permits::incrementAndGet);
        assertThat(permits.get()).isEqualTo(2);
        service.computeEmbeddingBatch("workspace", "source", "snapshot", 0, ignored -> {}, permits::incrementAndGet);
        assertThat(permits.get()).isEqualTo(3);
    }

    @Test
    void identicalBatchReusesQaVectorsButDifferentDocumentContextCannotReuseThem() {
        seedChunks(2);
        BatchEmbeddings first = compute(0);
        embeddings.requests.clear();
        BatchEmbeddings same = compute(0);

        assertThat(same.qaVectors()).isEqualTo(first.qaVectors());
        assertThat(embeddings.requests).extracting(List::size).containsExactly(1);
        jdbc.update("update source set title = '另一份资料'");
        embeddings.requests.clear();
        compute(0);
        assertThat(embeddings.requests).extracting(List::size).containsExactly(2, 1);
    }

    @Test
    void hashesTheExactTrimmedTruncatedProviderInputAndStillChecksFullInput() throws Exception {
        seedChunks(1);
        jdbc.update("update source set title = 'abcdef '");
        configureInputLimit(7);
        BatchEmbeddings batch = compute(0);

        assertThat(embeddings.requests.get(0)).containsExactly("abcdef");
        assertThat(batch.qaTextHashes()).containsExactly(hash("abcdef"));
        assertThat(batch.qaFullTextHashes().get(0)).isNotEqualTo(batch.qaTextHashes().get(0));
        jdbc.update("update source_chunk set content = '完全不同的末尾正文'");
        embeddings.requests.clear();
        compute(0);
        assertThat(embeddings.requests).extracting(List::size).containsExactly(1, 1);
    }

    @Test
    void streamsCheckpointBatchesToIndexWithoutChangingOriginalText() {
        seedChunks(65);
        BatchEmbeddings first = compute(0);
        BatchEmbeddings last = compute(1);
        Iterable<BatchEmbeddings> lazy = () -> new Iterator<>() {
            int position;
            public boolean hasNext() { return position < 2; }
            public BatchEmbeddings next() {
                if (position++ == 0) { return first; }
                verify(writer, times(64)).writeQaChunk(anyString(), any());
                verify(writer, never()).markSnapshotCurrent(anyString(), anyString());
                return last;
            }
        };

        assertThat(service.projectSnapshotBatches("workspace", "source", "snapshot", lazy).complete()).isTrue();
        ArgumentCaptor<RetrievalProjectionWriter.QaChunkDocument> docs = ArgumentCaptor.forClass(
                RetrievalProjectionWriter.QaChunkDocument.class);
        verify(writer, times(65)).writeQaChunk(anyString(), docs.capture());
        assertThat(docs.getAllValues().get(0).content()).isEqualTo("原文片段 0");
        assertThat(docs.getAllValues().get(0).contextText()).isEqualTo("本节介绍缓存一致性");
        verify(writer, times(2)).markSnapshotCurrent(anyString(), anyString());
    }

    @Test
    void missingCheckpointCannotPublishPartiallyWrittenSnapshot() {
        seedChunks(65);
        BatchEmbeddings first = compute(0);
        assertThatThrownBy(() -> service.projectSnapshotBatches("workspace", "source", "snapshot", List.of(first)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不完整");
        verify(writer, times(64)).writeQaChunk(anyString(), any());
        verify(writer, never()).markSnapshotCurrent(anyString(), anyString());
        verify(writer, times(2)).markSnapshotNotCurrent(anyString(), anyString());
    }

    @Test
    void mutatedOriginalOrChangedContextVersionInvalidatesExistingCheckpoint() {
        seedChunks(1);
        BatchEmbeddings batch = compute(0);
        jdbc.update("update source_chunk set content = '被修改的原文'");
        assertThatThrownBy(() -> service.projectSnapshotBatches("workspace", "source", "snapshot", List.of(batch)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("摘要");
        verify(writer, never()).writeQaChunk(anyString(), any());
        String oldVersion = service.currentBatchEmbeddingVersion();
        when(enhancer.currentContextPromptVersion()).thenReturn("context-v2");
        assertThat(service.currentBatchEmbeddingVersion()).isNotEqualTo(oldVersion);
        assertThatThrownBy(() -> service.projectSnapshotBatches("workspace", "source", "snapshot", List.of(batch)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("冻结版本");
    }

    @Test
    void nonFiniteOrDifferentModelVectorsAreRejectedBeforeCaching() {
        seedChunks(1);
        embeddings.vector = List.of(Float.NaN, 2f);
        assertThatThrownBy(() -> compute(0)).isInstanceOf(RetrievalProviderException.class);
        embeddings.vector = List.of(1f, 2f);
        embeddings.model = "different-model";
        assertThatThrownBy(() -> compute(0)).isInstanceOf(RetrievalProviderException.class);
        assertThat(jdbc.queryForObject("select count(*) from source_embedding_cache", Integer.class)).isZero();
    }

    @Test
    void defaultSynchronousProjectionActuallyEnhancesAndPersistsBeforeIndexing() {
        seedChunks(65);
        SimpleMeterRegistry metrics = installRealEnhancer();
        assertThat(service.projectSnapshot("workspace", "source", "snapshot").complete()).isTrue();
        assertThat(embeddings.requests).extracting(List::size).containsExactly(64, 1, 1);
        assertThat(embeddings.requests.get(0)).allMatch(text -> text.contains("所属缓存章节"));
        assertThat(jdbc.queryForObject("select count(*) from source_chunk where context_text = '所属缓存章节'", Integer.class))
                .isEqualTo(65);
        assertThat(metrics.get("noteweave.source.context.llm.calls").counter().count()).isEqualTo(2);
        ArgumentCaptor<RetrievalProjectionWriter.QaChunkDocument> docs = ArgumentCaptor.forClass(
                RetrievalProjectionWriter.QaChunkDocument.class);
        verify(writer, times(65)).writeQaChunk(anyString(), docs.capture());
        assertThat(docs.getAllValues()).allMatch(doc -> doc.contextText().equals("所属缓存章节"));
        assertThat(service.effectiveProjectionEmbeddingVersion()).isEqualTo(service.currentBatchEmbeddingVersion());
    }

    @Test
    void backfillEntryUsesExplicitTargetsAndRestoresStoredContextWithoutAnotherModelCall() {
        seedChunks(65);
        SimpleMeterRegistry metrics = installRealEnhancer();
        assertThat(service.projectSnapshotToIndexes("workspace", "source", "snapshot", "qa-build-one", "note-build-one")
                .complete()).isTrue();
        embeddings.requests.clear();
        assertThat(service.projectSnapshotToIndexes("workspace", "source", "snapshot", "qa-build-two", "note-build-two")
                .complete()).isTrue();
        verify(writer, times(65)).writeQaChunk(org.mockito.ArgumentMatchers.eq("qa-build-one"), any());
        verify(writer, times(65)).writeQaChunk(org.mockito.ArgumentMatchers.eq("qa-build-two"), any());
        verify(writer).writeNoteSource(org.mockito.ArgumentMatchers.eq("note-build-two"), any());
        assertThat(metrics.get("noteweave.source.context.llm.calls").counter().count()).isEqualTo(2);
        assertThat(embeddings.requests).extracting(List::size).containsExactly(1);
    }

    @Test
    void compatibilityComputeEntryStillBoundsEachProviderCallAndRunsContext() {
        seedChunks(130);
        SimpleMeterRegistry metrics = installRealEnhancer();
        var snapshot = service.computeEmbeddings("workspace", "source", "snapshot");
        assertThat(snapshot.chunkIds()).hasSize(130);
        assertThat(embeddings.requests).extracting(List::size).containsExactly(64, 64, 2, 1);
        assertThat(embeddings.requests.get(1)).allMatch(text -> text.contains("所属缓存章节"));
        assertThat(metrics.get("noteweave.source.context.llm.calls").counter().count()).isEqualTo(3);
    }

    private SimpleMeterRegistry installRealEnhancer() {
        ObjectMapper mapper = new ObjectMapper();
        ChatLlmClient llm = mock(ChatLlmClient.class);
        when(llm.isEnabled()).thenReturn(true);
        when(llm.streamChat(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), any())).thenAnswer(call -> {
            List<Object> results = new ArrayList<>();
            for (var row : mapper.readTree((String) call.getArgument(1)).path("chunks")) {
                results.add(java.util.Map.of("chunk_id", row.path("chunk_id").textValue(), "context", "所属缓存章节"));
            }
            return mapper.writeValueAsString(results);
        });
        when(properties.llm()).thenReturn(new NoteWeaveProperties.Llm(true, "http://localhost/v1", "context-model", "", 60));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        SourceChunkContextEnhancer real = new SourceChunkContextEnhancer(jdbc, llm, mapper,
                new SourceChunkContextProperties(true, "source-context-v1", 1000, 48000, 600, 12000), properties,
                metrics, new DataSourceTransactionManager(jdbc.getDataSource()));
        service = new SourceRetrievalProjectionService(jdbc, embeddings, repository, manager, writer, properties,
                new SourceTagCodec(mapper), new SourceEmbeddingCacheRepository(jdbc, mapper), real);
        return metrics;
    }

    private BatchEmbeddings compute(int batch) {
        return service.computeEmbeddingBatch("workspace", "source", "snapshot", batch, calls -> { });
    }

    private void configureInputLimit(int max) {
        when(properties.embedding()).thenReturn(new NoteWeaveProperties.Embedding(true, "http://localhost:18105/v1",
                "model", "secret-not-in-version", 2, 32, 10, 20, max, 3));
    }

    private void seedChunks(int count) {
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    insert into source_chunk(id, workspace_id, source_id, source_snapshot_id,
                        chunk_no, heading, content, structure_metadata_json)
                    values (?, 'workspace', 'source', 'snapshot', ?, '章节', ?, null)
                    """,
                    "chunk-" + i, i, "原文片段 " + i);
        }
    }

    private String hash(String text) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static class RecordingEmbeddings implements EmbeddingClient {
        final List<List<String>> requests = new ArrayList<>();
        List<Float> vector = List.of(1f, 2f);
        String model = "model";
        public boolean isEnabled() { return true; }
        public EmbeddingResult embedQuery(String text) { return embedDocuments(List.of(text)); }
        public EmbeddingResult embedDocumentsWithBudget(List<String> inputs, Runnable beforeRequest) {
            beforeRequest.run();
            return embedDocuments(inputs);
        }
        public EmbeddingResult embedDocuments(List<String> inputs) {
            requests.add(List.copyOf(inputs));
            return new EmbeddingResult(inputs.stream().map(ignored -> vector).toList(), model, 2, inputs.size());
        }
    }
}
