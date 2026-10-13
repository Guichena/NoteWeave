package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskCommandPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 验证结构文件的阶段交接、身份隔离和不可拆元素的真实持久化。 */
class SourceStructuredParsingTest {
    @TempDir Path temporary;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();
    private final MemoryStorage storage = new MemoryStorage();
    private String hash;
    private Path original;
    private final String table = "| 实验项 | 测量结果 |\n" + "| 缓存 | 真实测量 20% |\n".repeat(30);

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:structured-parse-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table file_object(id varchar(64) primary key, mime_type varchar(160));
                create table source(id varchar(64) primary key, workspace_id varchar(64), file_object_id varchar(64),
                    title varchar(300), source_type varchar(64), status varchar(32), parse_status varchar(32),
                    index_status varchar(32), summary varchar(1000), tags_json clob, metadata_json clob,
                    updated_by varchar(120), updated_at timestamp);
                create table source_snapshot(id varchar(64) primary key, source_id varchar(64), file_object_id varchar(64),
                    version_no int, object_key varchar(500), sha256 varchar(64), parse_status varchar(32),
                    index_status varchar(32), processing_stage varchar(32));
                create table source_chunk(id varchar(64) primary key, workspace_id varchar(64), source_id varchar(64),
                    source_snapshot_id varchar(64), chunk_no int, heading varchar(300), content clob,
                    token_estimate int, location_info varchar(300), page_start int, page_end int,
                    structure_metadata_json clob, projection_status varchar(32), projected_at timestamp);
                create table source_window(id varchar(64) primary key, source_chunk_id varchar(64), window_no int,
                    content clob, location_info varchar(300));
                create table task(id varchar(64) primary key, task_type varchar(64), target_id varchar(64), created_at timestamp);
                create table task_outbox(id varchar(64) primary key, task_id varchar(64), topic varchar(160),
                    message_key varchar(160), payload_json clob, status varchar(32));
                """);
        original = temporary.resolve("report.pdf");
        Files.writeString(original, table, StandardCharsets.UTF_8);
        hash = SourceDocumentTextExtractor.sha256(original);
        storage.write("noteweave-source", "original-1", Files.readAllBytes(original));
        jdbc.update("insert into file_object(id, mime_type) values ('file-1', 'application/pdf')");
        jdbc.update("""
                insert into source(id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status)
                values ('source-1', 'workspace-1', 'file-1', 'report.pdf', 'PDF', 'PROCESSING', 'PENDING', 'PENDING')
                """);
        insertSnapshot("snapshot-1", 1);
    }

    @Test
    void parsedStructureReachesChunkMetadataAndTheAtomicReadingWindow() throws Exception {
        SourceParseService service = service((request, file) -> parsed(request), true);
        assertThat(service.parseAndIndexAsyncIfProcessable("workspace-1", "source-1", "snapshot-1")).isTrue();
        assertThat(storage.exists(SourcePipelineStages.BUCKET_DERIVED, parsedKey())).isTrue();
        assertThat(count("source_chunk")).isZero();
        assertThat(count("task_outbox")).isEqualTo(1);
        var stored = mapper.readValue(storage.read(SourcePipelineStages.BUCKET_DERIVED, parsedKey()),
                SourceParseService.StoredParsedDocument.class);
        assertThat(stored.sha256()).isEqualTo(hash);
        assertThat(stored.document().parser()).isEqualTo("DOCLING");
        assertThat(stored.document().elements()).hasSize(2);

        assertThat(service.chunkStage("workspace-1", "source-1", "snapshot-1", textKey(), "application/pdf", 2)).isTrue();
        Map<String, Object> atomic = jdbc.queryForMap("""
                select id, content, page_start, page_end, structure_metadata_json from source_chunk
                where source_snapshot_id = 'snapshot-1' and content = ?
                """, table);
        assertThat(atomic.get("PAGE_START")).isEqualTo(2);
        assertThat(atomic.get("PAGE_END")).isEqualTo(2);
        String metadata = jdbc.queryForObject("select structure_metadata_json from source_chunk where id = ?",
                String.class, atomic.get("ID"));
        assertThat(mapper.readTree(metadata).path("indivisible").asBoolean()).isTrue();
        assertThat(mapper.readTree(metadata).path("exceeds_token_budget").asBoolean()).isTrue();
        assertThat(mapper.readTree(metadata).path("elements").get(0).path("bbox").path("coord_origin").asText())
                .isEqualTo("BOTTOMLEFT");
        assertThat(jdbc.queryForObject("select content from source_window where source_chunk_id = ?",
                String.class, atomic.get("ID"))).isEqualTo(table);
        var sourceMeta = mapper.readTree(jdbc.queryForObject("select metadata_json from source where id = 'source-1'", String.class));
        assertThat(sourceMeta.path("parser").asText()).isEqualTo("DOCLING");
        assertThat(sourceMeta.path("truncated").asBoolean()).isTrue();
        assertThat(sourceMeta.path("warnings").get(0).asText()).isEqualTo("MAX_CHARS_REACHED");
        assertThat(service.chunkStage("workspace-1", "source-1", "snapshot-1", textKey(), "application/pdf", 2)).isFalse();
    }

    @Test
    void parsedDocumentIdentityMismatchRollsBackWithoutPublishingChunks() throws Exception {
        SourceParseService service = service((request, file) -> parsed(request), true);
        service.parseAndIndexAsyncIfProcessable("workspace-1", "source-1", "snapshot-1");
        var document = mapper.readValue(storage.read(SourcePipelineStages.BUCKET_DERIVED, parsedKey()),
                SourceParseService.StoredParsedDocument.class);
        storage.write(SourcePipelineStages.BUCKET_DERIVED, parsedKey(), mapper.writeValueAsBytes(
                new SourceParseService.StoredParsedDocument(1, "workspace-1", "source-1", "snapshot-previous",
                        hash, document.document())));

        assertThatThrownBy(() -> service.chunkStage("workspace-1", "source-1", "snapshot-1", textKey(), "application/pdf", 2))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("SOURCE_PARSED_DOCUMENT_IDENTITY_MISMATCH"));
        assertThat(count("source_chunk")).isZero();
        assertThat(stage()).isEqualTo("CHUNKING");
    }

    @Test
    void parserResultIsDiscardedWhenANewerSnapshotArrivesDuringExtraction() {
        SourceParseService service = service((request, file) -> {
            insertSnapshot("snapshot-2", 2);
            return parsed(request);
        }, true);

        assertThat(service.parseAndIndexAsyncIfProcessable("workspace-1", "source-1", "snapshot-1")).isFalse();
        assertThat(count("source_chunk")).isZero();
        assertThat(count("task_outbox")).isZero();
        assertThat(storage.exists(SourcePipelineStages.BUCKET_DERIVED, parsedKey())).isFalse();
        assertThat(stage()).isNull();
    }

    @Test
    void chunkStageRejectsATextObjectFromAnotherSnapshot() {
        jdbc.update("update source_snapshot set processing_stage = 'CHUNKING' where id = 'snapshot-1'");
        SourceParseService service = service((request, file) -> parsed(request), true);
        assertThatThrownBy(() -> service.chunkStage("workspace-1", "source-1", "snapshot-1",
                SourcePipelineStages.extractedTextKey("workspace-1", "source-1", "snapshot-old"), "application/pdf", 2))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("SOURCE_PARSED_DOCUMENT_IDENTITY_MISMATCH"));
        assertThat(count("source_chunk")).isZero();
    }

    @Test
    void oldTextWithoutAStructureFileRemainsReadableForTheSameSnapshot() {
        jdbc.update("update source_snapshot set processing_stage = 'CHUNKING' where id = 'snapshot-1'");
        storage.write(SourcePipelineStages.BUCKET_DERIVED, textKey(), "第一章 检索\n兼容旧文本资料。".getBytes(StandardCharsets.UTF_8));
        SourceParseService service = service((request, file) -> parsed(request), true);

        assertThat(service.chunkStage("workspace-1", "source-1", "snapshot-1", textKey(), "application/pdf", 2)).isTrue();
        assertThat(jdbc.queryForList("select content from source_chunk", String.class)).anySatisfy(text ->
                assertThat(text).contains("兼容旧文本资料"));
        assertThat(jdbc.queryForList("select structure_metadata_json from source_chunk", String.class)).containsOnlyNulls();
    }

    @Test
    void synchronousPathPersistsStructureWithoutUsingTheByteArrayEntry() {
        SourceParseService service = service((request, file) -> parsed(request), false);
        service.parseAndIndex("workspace-1", "source-1", "snapshot-1", original);
        assertThat(storage.exists(SourcePipelineStages.BUCKET_DERIVED, parsedKey())).isTrue();
        assertThat(count("source_chunk")).isPositive();
        assertThat(stage()).isEqualTo("READY");
    }

    @Test
    void textMimeParametersAreNormalizedBeforeStructureIsHandedToTheChunkStage() throws Exception {
        jdbc.update("update file_object set mime_type = ' Text/Plain; charset=utf-8 ' where id = 'file-1'");
        var service = service((request, file) -> {
            assertThat(request.mimeType()).isEqualTo("text/plain");
            return parsed(request);
        }, true);

        assertThat(service.parseAndIndexAsyncIfProcessable("workspace-1", "source-1", "snapshot-1")).isTrue();
        var stored = mapper.readValue(storage.read(SourcePipelineStages.BUCKET_DERIVED, parsedKey()),
                SourceParseService.StoredParsedDocument.class);
        assertThat(stored.document().mimeType()).isEqualTo("text/plain");
        assertThat(service.chunkStage("workspace-1", "source-1", "snapshot-1", textKey(), "text/plain", 2)).isTrue();
        assertThat(count("source_chunk")).isPositive();
        assertThat(stage()).isEqualTo("READY");
        var metadata = mapper.readTree(jdbc.queryForObject("select metadata_json from source where id='source-1'", String.class));
        assertThat(metadata.path("mime_type").asText()).isEqualTo("text/plain");
    }

    private SourceDocumentParserPort.ParsedDocument parsed(SourceDocumentParserPort.Request request) {
        return new SourceDocumentParserPort.ParsedDocument(1, request.workspaceId(), request.sourceId(), request.snapshotId(),
                request.sha256(), request.mimeType(), "# 实验报告\n\n" + table, 2,
                List.of(new DocumentElement(DocumentElement.ElementType.TITLE, "实验报告", 1, null, 1),
                        new DocumentElement(DocumentElement.ElementType.TABLE, table, 2,
                                new DocumentElement.BoundingBox(10, 700, 500, 100, DocumentElement.CoordOrigin.BOTTOMLEFT), null)),
                "DOCLING", true, List.of("MAX_CHARS_REACHED"));
    }

    private SourceParseService service(SourceDocumentParserPort parser, boolean asynchronous) {
        AuditActorProvider actor = mock(AuditActorProvider.class);
        when(actor.currentOrSystem("SOURCE_PARSE")).thenReturn("system:test");
        var extractor = new SourceDocumentTextExtractor(parser,
                new SourceDocumentParserProperties(true, "http://localhost:18103", 180, 3, 5000, ""));
        return new SourceParseService(jdbc, new DocumentChunker(120, 30), extractor, mapper, storage, () -> asynchronous,
                new NoteWeaveProperties(null, null, null, null,
                        new NoteWeaveProperties.Elasticsearch(false, "localhost", 9200, "http", "noteweave_chunk"), null),
                mock(TaskCommandPort.class), new DataSourceTransactionManager(dataSource), actor,
                mock(SourceCatalogVersionService.class), mock(ApplicationEventPublisher.class));
    }

    private void insertSnapshot(String id, int version) {
        jdbc.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status)
                values (?, 'source-1', 'file-1', ?, 'original-1', ?, 'PENDING', 'PENDING')
                """, id, version, hash);
    }

    private String parsedKey() { return SourceParseService.parsedDocumentKey("workspace-1", "source-1", "snapshot-1"); }
    private String textKey() { return SourcePipelineStages.extractedTextKey("workspace-1", "source-1", "snapshot-1"); }
    private int count(String tableName) { return jdbc.queryForObject("select count(*) from " + tableName, Integer.class); }
    private String stage() { return jdbc.queryForObject("select processing_stage from source_snapshot where id='snapshot-1'", String.class); }

    private static final class MemoryStorage implements ObjectStorage {
        private final Map<String, byte[]> objects = new HashMap<>();
        private String key(String bucket, String name) { return bucket + "/" + name; }
        @Override public Path write(String bucket, String name, byte[] content) {
            objects.put(key(bucket, name), content.clone()); return Path.of(name);
        }
        @Override public byte[] read(String bucket, String name) { return objects.get(key(bucket, name)).clone(); }
        @Override public boolean exists(String bucket, String name) { return objects.containsKey(key(bucket, name)); }
        @Override public void delete(String bucket, String name) { objects.remove(key(bucket, name)); }
        @Override public List<StoredObject> list(String bucket, String prefix, String afterKey, int limit) { return List.of(); }
        @Override public void writeFile(String bucket, String name, Path file) {
            try { write(bucket, name, Files.readAllBytes(file)); } catch (IOException exception) { throw new IllegalStateException(exception); }
        }
        @Override public void readToFile(String bucket, String name, Path file) {
            try { Files.write(file, read(bucket, name)); } catch (IOException exception) { throw new IllegalStateException(exception); }
        }
        @Override public String backendName() { return "TEST_FIXTURE"; }
    }
}
