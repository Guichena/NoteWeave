package com.noteweave.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.noteweave.retrieval.index.ElasticsearchRetrievalProjectionWriter;
import com.noteweave.retrieval.index.RetrievalIndexManager;
import com.noteweave.retrieval.index.RetrievalIndexNames;
import com.noteweave.retrieval.index.RetrievalProjectionWriter.NoteSourceDocument;
import com.noteweave.retrieval.index.RetrievalProjectionWriter.QaChunkDocument;
import com.noteweave.retrieval.note.ElasticsearchNoteSourceSearchAdapter;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import com.noteweave.retrieval.qa.ElasticsearchQaHybridSearchAdapter;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaKeywordQuery;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaVectorQuery;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "NOTEWEAVE_RUN_LIVE_RETRIEVAL_TESTS", matches = "true")
class RetrievalLiveElasticsearchIntegrationTest {
    @Test
    void liveElasticsearchSupportsDualRecallCurrentSnapshotAndAtomicAliasSwitch() throws Exception {
        String workspace = "live-" + UUID.randomUUID().toString().substring(0, 8);
        String qaV1 = RetrievalIndexNames.physical(ProjectionType.QA_CHUNK, "live-v1", "embed:3", workspace);
        String noteV1 = RetrievalIndexNames.physical(ProjectionType.NOTE_SOURCE, "live-v1", "embed:3", workspace);
        String qaV2 = RetrievalIndexNames.physical(ProjectionType.QA_CHUNK, "live-v2", "embed:3", workspace);
        String noteV2 = RetrievalIndexNames.physical(ProjectionType.NOTE_SOURCE, "live-v2", "embed:3", workspace);
        try (RestClient rest = RestClient.builder(new HttpHost("127.0.0.1", 9200, "http")).build();
             RestClientTransport transport = new RestClientTransport(rest, new JacksonJsonpMapper())) {
            ElasticsearchClient client = new ElasticsearchClient(transport);
            RetrievalIndexManager manager = new RetrievalIndexManager(client);
            ElasticsearchRetrievalProjectionWriter writer = new ElasticsearchRetrievalProjectionWriter(client);
            try {
                manager.createIndex(ProjectionType.QA_CHUNK, qaV1, 3);
                manager.createIndex(ProjectionType.NOTE_SOURCE, noteV1, 3);
                writer.writeQaChunk(qaV1, qa(workspace, "chunk-current", "source-current", "snapshot-current",
                        "Checkpoint recovery resumes interrupted research", false, List.of(1f, 0f, 0f)));
                writer.writeQaChunk(qaV1, qa(workspace, "chunk-stale", "source-stale", "snapshot-stale",
                        "Checkpoint recovery stale copy", false, List.of(1f, 0f, 0f)));
                writer.writeQaChunk(qaV1, qa(workspace, "chunk-other", "source-other", "snapshot-other",
                        "Typography and color tokens", true, List.of(0f, 1f, 0f)));
                writer.writeNoteSource(noteV1, note(workspace, "source-current", "snapshot-current",
                        "Durable checkpoints", false, List.of(1f, 0f, 0f)));
                writer.writeNoteSource(noteV1, note(workspace, "source-stale", "snapshot-stale",
                        "Old checkpoints", false, List.of(1f, 0f, 0f)));
                manager.switchAliases(Map.of(
                        RetrievalIndexNames.alias(ProjectionType.QA_CHUNK, workspace), qaV1,
                        RetrievalIndexNames.alias(ProjectionType.NOTE_SOURCE, workspace), noteV1));
                writer.markSnapshotCurrent(qaV1, "snapshot-current");
                writer.markSnapshotCurrent(noteV1, "snapshot-current");
                assertThat(manager.resolveWriteIndex(
                        RetrievalIndexNames.alias(ProjectionType.QA_CHUNK, workspace), "missing"))
                        .isEqualTo(qaV1);
                client.indices().refresh(refresh -> refresh.index(List.of(qaV1, noteV1)));

                ElasticsearchQaHybridSearchAdapter qaSearch = new ElasticsearchQaHybridSearchAdapter(client);
                var qaVectorHits = qaSearch.vectorRetrieve(new QaVectorQuery(
                        workspace, "resume research", List.of(1f, 0f, 0f), Set.of(), 5, 0));
                assertThat(qaVectorHits).first().extracting(hit -> hit.chunkId()).isEqualTo("chunk-current");
                assertThat(qaVectorHits).extracting(hit -> hit.chunkId()).doesNotContain("chunk-stale");
                assertThat(qaSearch.keywordRetrieve(new QaKeywordQuery(
                        workspace, "Checkpoint recovery", Set.of("source-current"), 5, 0)))
                        .extracting(hit -> hit.chunkId()).containsExactly("chunk-current");
                ElasticsearchNoteSourceSearchAdapter noteSearch = new ElasticsearchNoteSourceSearchAdapter(client);
                var noteSemanticHits = noteSearch.semanticRetrieve(workspace, List.of(1f, 0f, 0f), 5);
                assertThat(noteSemanticHits).first().extracting(hit -> hit.sourceId()).isEqualTo("source-current");
                assertThat(noteSemanticHits).extracting(hit -> hit.sourceId()).doesNotContain("source-stale");
                assertThat(noteSearch.metadataRetrieve(workspace, "Durable checkpoints", 5))
                        .extracting(hit -> hit.sourceId()).containsExactly("source-current");

                manager.createIndex(ProjectionType.QA_CHUNK, qaV2, 3);
                manager.createIndex(ProjectionType.NOTE_SOURCE, noteV2, 3);
                writer.writeQaChunk(qaV2, qa(workspace, "chunk-v2", "source-v2", "snapshot-v2",
                        "Checkpoint recovery generation two", true, List.of(1f, 0f, 0f)));
                writer.writeNoteSource(noteV2, note(workspace, "source-v2", "snapshot-v2",
                        "Durable checkpoints generation two", true, List.of(1f, 0f, 0f)));
                client.indices().refresh(refresh -> refresh.index(List.of(qaV2, noteV2)));
                manager.switchAliases(Map.of(
                        RetrievalIndexNames.alias(ProjectionType.QA_CHUNK, workspace), qaV2,
                        RetrievalIndexNames.alias(ProjectionType.NOTE_SOURCE, workspace), noteV2));
                assertThat(manager.resolveWriteIndex(
                        RetrievalIndexNames.alias(ProjectionType.QA_CHUNK, workspace), "missing"))
                        .isEqualTo(qaV2);

                assertThat(qaSearch.vectorRetrieve(new QaVectorQuery(
                        workspace, "resume", List.of(1f, 0f, 0f), Set.of(), 5, 0)))
                        .extracting(hit -> hit.chunkId()).containsExactly("chunk-v2");
                assertThat(noteSearch.semanticRetrieve(workspace, List.of(1f, 0f, 0f), 5))
                        .extracting(hit -> hit.sourceId()).containsExactly("source-v2");
            } finally {
                client.indices().delete(delete -> delete
                        .index(List.of(qaV1, noteV1, qaV2, noteV2))
                        .ignoreUnavailable(true));
            }
        }
    }

    private QaChunkDocument qa(String workspace, String chunk, String source, String snapshot,
                               String content, boolean current, List<Float> vector) {
        return new QaChunkDocument(workspace, source, snapshot, chunk, 0, "heading", "title",
                "PDF", content, "hash", "embed", 3, "embed:3", "live-v1", current, vector);
    }

    private NoteSourceDocument note(String workspace, String source, String snapshot, String title,
                                    boolean current, List<Float> vector) {
        return new NoteSourceDocument(workspace, source, snapshot, title, "PDF", "summary",
                List.of("checkpoint"), "metadata", List.of("recovery"), List.of(), 1, 1,
                "hash", "embed", 3, "embed:3", "live-v1", current, vector);
    }
}
