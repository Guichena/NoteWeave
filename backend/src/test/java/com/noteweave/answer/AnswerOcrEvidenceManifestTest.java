package com.noteweave.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.security.AuditActorProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 使用真实迁移和公开回答准备入口，验证来源在回答保存时冻结。 */
class AnswerOcrEvidenceManifestTest {
    private JdbcTemplate jdbc;
    private AnswerRunService service;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:ocr-manifest-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into workspace(id, owner_id, name, status) values ('w', 'local-user', 'OCR工作台', 'ACTIVE')");
        jdbc.update("insert into workspace(id, owner_id, name, status) values ('foreign-w', 'local-user', '另一工作台', 'ACTIVE')");
        jdbc.update("""
                insert into conversation(id, workspace_id, title, conversation_type, status)
                values ('conversation', 'w', '来源测试', 'QA', 'ACTIVE')
                """);
        jdbc.update("""
                insert into conversation_message(id, conversation_id, workspace_id, message_seq, role, content)
                values ('query', 'conversation', 'w', 1, 'USER', '问题'),
                    ('answer', 'conversation', 'w', 2, 'ASSISTANT', '')
                """);
        source("w", "source", "snapshot", "chunk", "OCR", 0.4);
        source("foreign-w", "foreign-source", "foreign-snapshot", "foreign-chunk", "OCR", 0.9);
        AuditActorProvider actor = mock(AuditActorProvider.class);
        when(actor.currentOrSystem("ANSWER")).thenReturn("system:test");
        service = new AnswerRunService(jdbc, actor, new SimpleMeterRegistry(), mapper);
    }

    @Test
    void answerCopiesActualChunkSourceThenIgnoresLaterChunkChanges() {
        String run = prepare(List.of(evidence("passage:chunk", "source", "snapshot", "chunk",
                Map.of("text_origin", "TEXT_LAYER", "ocr_confidence", "1.0"))));
        var saved = service.evidenceManifest("w", run).evidence().get(0);
        assertThat(saved.textOrigin()).isEqualTo("OCR");
        assertThat(saved.ocrConfidence()).isEqualTo(0.4);
        jdbc.update("update source_chunk set text_origin = 'TEXT_LAYER', ocr_confidence = null where id = 'chunk'");
        var historical = service.evidenceManifest("w", run).evidence().get(0);
        assertThat(historical.textOrigin()).isEqualTo("OCR");
        assertThat(historical.ocrConfidence()).isEqualTo(0.4);
        var json = mapper.valueToTree(historical);
        assertThat(json.path("text_origin").textValue()).isEqualTo("OCR");
        assertThat(json.path("ocr_confidence").doubleValue()).isEqualTo(0.4);
    }

    @Test
    void completePassageIdentityPreventsCrossWorkspaceSnapshotAndSourceProvenanceLeaks() {
        String run = prepare(List.of(
                evidence("wrong-source", "another-source", "snapshot", "chunk", Map.of()),
                evidence("wrong-snapshot", "source", "another-snapshot", "chunk", Map.of()),
                evidence("wrong-workspace", "foreign-source", "foreign-snapshot", "foreign-chunk", Map.of()),
                evidence("real", "source", "snapshot", "chunk", Map.of())));
        var evidence = service.evidenceManifest("w", run).evidence();
        assertThat(evidence).hasSize(4);
        for (int i = 0; i < 3; i++) {
            assertThat(evidence.get(i).textOrigin()).isNull();
            assertThat(evidence.get(i).ocrConfidence()).isNull();
        }
        assertThat(evidence.get(3).textOrigin()).isEqualTo("OCR");
        assertThat(evidence.get(3).ocrConfidence()).isEqualTo(0.4);
    }

    @Test
    void unknownHistoricalEvidenceDoesNotGainLabelsAfterSourceBackfill() {
        jdbc.update("update source_chunk set text_origin = null, ocr_confidence = null where id = 'chunk'");
        String run = prepare(List.of(evidence("legacy", "source", "snapshot", "chunk",
                Map.of("text_origin", "OCR", "ocr_confidence", "0.99"))));
        jdbc.update("update source_chunk set text_origin = 'OCR', ocr_confidence = 0.99 where id = 'chunk'");
        var legacy = service.evidenceManifest("w", run).evidence().get(0);
        assertThat(legacy.textOrigin()).isNull();
        assertThat(legacy.ocrConfidence()).isNull();
        var json = mapper.valueToTree(legacy);
        assertThat(json.has("text_origin")).isFalse();
        assertThat(json.has("ocr_confidence")).isFalse();
    }

    @Test
    void realZeroConfidenceAndNativeTextLayerDoNotBecomeFakeDefaults() {
        jdbc.update("update source_chunk set ocr_confidence = 0 where id = 'chunk'");
        source("w", "native-source", "native-snapshot", "native-chunk", "TEXT_LAYER", null);
        String run = prepare(List.of(evidence("zero", "source", "snapshot", "chunk", Map.of()),
                evidence("native", "native-source", "native-snapshot", "native-chunk", Map.of())));
        var evidence = service.evidenceManifest("w", run).evidence();
        assertThat(evidence.get(0).textOrigin()).isEqualTo("OCR");
        assertThat(evidence.get(0).ocrConfidence()).isZero();
        assertThat(evidence.get(1).textOrigin()).isEqualTo("TEXT_LAYER");
        assertThat(evidence.get(1).ocrConfidence()).isNull();
        assertThat(mapper.valueToTree(evidence.get(1)).has("ocr_confidence")).isFalse();
    }

    private String prepare(List<EvidenceBundle.Evidence> evidence) {
        String run = service.createAndStartRetrieval("w", "conversation", "QA", "query", null);
        EvidenceBundle bundle = new EvidenceBundle("bundle", "test-v1", evidence, false, List.of(), Instant.now());
        service.prepareGeneration("w", run, "answer", UUID.randomUUID().toString(), "", "test-model", bundle,
                "test-prompt-v1", 1000, null);
        return run;
    }

    private EvidenceBundle.Evidence evidence(String evidenceId, String source, String snapshot, String chunk,
                                             Map<String, String> metadata) {
        return new EvidenceBundle.Evidence(evidenceId, "PASSAGE", source, snapshot, chunk, "", "", "实际资料", "真实摘录",
                "page:1", 1, 1, 1, "workspace-source:" + source, Instant.now(), "test", 4, metadata);
    }

    private void source(String workspace, String source, String snapshot, String chunk, String origin, Double confidence) {
        String file = UUID.randomUUID().toString();
        String hash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type)
                values (?, ?, ?, ?, 1, 'application/pdf')
                """, file, workspace, "test/" + source, hash);
        jdbc.update("""
                insert into source(id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status)
                values (?, ?, ?, '实际资料', 'USER_UPLOAD', 'READY', 'PARSED', 'INDEXED')
                """, source, workspace, file);
        jdbc.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status)
                values (?, ?, ?, 1, ?, ?, 'PARSED', 'INDEXED')
                """, snapshot, source, file, "test/" + source, hash);
        jdbc.update("""
                insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no, content,
                    token_estimate, text_origin, ocr_confidence)
                values (?, ?, ?, ?, 0, '真实原文', 4, ?, ?)
                """, chunk, workspace, source, snapshot, origin, confidence);
    }
}
