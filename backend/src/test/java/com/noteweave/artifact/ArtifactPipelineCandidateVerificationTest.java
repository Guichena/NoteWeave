package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.worker.WorkerCompleteRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArtifactPipelineCandidateVerificationTest {
    @SuppressWarnings("unchecked")
    private Map<String, Object> payload() throws Exception {
        return new ObjectMapper().readValue(Files.readAllBytes(Path.of(
                "../workers/artifact-worker/tests/pipeline/pipeline-result-v1.json")), Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pythonResultPassesPipelineAndExistingCandidateValidation() throws Exception {
        Map<String, Object> payload = payload();
        ArtifactCandidateVerificationPolicy.validatePipeline(payload, "DOC", Set.of("source"));
        String digest = String.valueOf(((Map<?, ?>) payload.get("candidate")).get("catalog_digest"));
        WorkerCompleteRequest request = new WorkerCompleteRequest("MARKDOWN", "缓存学习材料", payload, "测试", List.of());
        assertThatCode(() -> ArtifactCandidate.from("pipeline-task", "input", digest, "DOC", request,
                (String) payload.get("markdown"))).doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsWrongVersionShapeOrSourceRange() throws Exception {
        Map<String, Object> payload = payload();
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validatePipeline(payload, "TABLE", Set.of("source")))
                .hasMessageContaining("形态");
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validatePipeline(payload, "DOC", Set.of("outside")))
                .hasMessageContaining("来源");
        ((Map<String, Object>) payload.get("ir")).put("schema_version", 99);
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validatePipeline(payload, "DOC", Set.of("source")))
                .hasMessageContaining("版本");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsInventedSentenceIdsAndChangedNumbers() throws Exception {
        Map<String, Object> payload = payload();
        Map<String, Object> ir = (Map<String, Object>) payload.get("ir");
        Map<String, Object> section = ((List<Map<String, Object>>) ir.get("sections")).get(0);
        Map<String, Object> block = ((List<Map<String, Object>>) section.get("blocks")).get(0);
        Map<String, Object> statement = ((List<Map<String, Object>>) block.get("statements")).get(0);
        statement.put("anchors", List.of("missing"));
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validatePipeline(payload, "DOC", Set.of("source")))
                .hasMessageContaining("素材包以外");
        statement.put("anchors", List.of("s0001"));
        statement.put("text", "缓存有效期为99秒。");
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validatePipeline(payload, "DOC", Set.of("source")))
                .hasMessageContaining("数字");
    }
}
