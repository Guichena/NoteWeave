package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArtifactCandidateVerificationPolicyTest {
    @SuppressWarnings("unchecked")
    private Map<String, Object> payload() throws Exception {
        return new ObjectMapper().readValue(Files.readAllBytes(Path.of(
                "../workers/artifact-worker/tests/pipeline/pipeline-result-v1.json")), Map.class);
    }

    @Test
    void acceptsCheckedSharedPipelinePayloadAndRejectsMissingPayload() throws Exception {
        assertThatCode(() -> ArtifactCandidateVerificationPolicy.validatePipeline(payload(), "DOC", Set.of("source")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validatePipeline(null, "DOC", Set.of("source")))
                .hasMessageContaining("流水线");
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "contract_status", "evidence_status"})
    @SuppressWarnings("unchecked")
    void legacyDeliveryFlagCannotBypassFailedChecks(String field) throws Exception {
        var payload = payload();
        ((Map<String, Object>) payload.get("verification")).put(field, "FAIL");
        payload.put("delivery_status", "DELIVERED_WITH_UNRESOLVED_CHECKS");
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validatePipeline(payload, "DOC", Set.of("source")))
                .hasMessageContaining("最终校验");
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateOrForeignEvidenceCannotPassWithASuccessStatus() throws Exception {
        var payload = payload();
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validatePipeline(payload, "DOC", Set.of("foreign")))
                .hasMessageContaining("来源");
        var pack = (Map<String, Object>) payload.get("evidence");
        var sentences = (List<Map<String, Object>>) pack.get("sentences");
        pack.put("sentences", List.of(sentences.get(0), sentences.get(0)));
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validatePipeline(payload, "DOC", Set.of("source")))
                .hasMessageContaining("编号重复");
    }

    @Test
    @SuppressWarnings("unchecked")
    void moreThanOneRepairOrMissingStageIsRejected() throws Exception {
        var payload = payload();
        var stats = (Map<String, Object>) payload.get("pipeline_stats");
        stats.put("repair_calls", 2);
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validatePipeline(payload, "DOC", Set.of("source")))
                .hasMessageContaining("阶段或修复");
        stats.put("repair_calls", 0);
        stats.put("stages", Map.of("gather", Map.of()));
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validatePipeline(payload, "DOC", Set.of("source")))
                .hasMessageContaining("阶段或修复");
    }
}
