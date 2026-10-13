package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ArtifactCandidateVerificationPolicyTest {
    private Map<String, Object> statement(String label, String assessment) {
        return new LinkedHashMap<>(Map.of("text", "已有资料中的陈述", "label", label,
                "assessment_status", assessment, "sufficiently_supported", "SUPPORTED".equals(label),
                "citations", List.of(Map.of("chunk_id", "chunk-1", "label", "SUPPORTED", "quote", "连续原文"))));
    }

    private Map<String, Object> payload() {
        Map<String, Object> verification = new LinkedHashMap<>(Map.of(
                "status", "PASS", "contract_status", "PASS", "evidence_status", "PASS",
                "repair_rounds", List.of(), "failed_checks", List.of(), "llm_calls", 1,
                "citation_recall", 1.0, "citation_precision", 1.0,
                "sections", List.of(new LinkedHashMap<>(Map.of("heading", "概览", "support", "SUPPORTED",
                        "statements", List.of(statement("SUPPORTED", "ASSESSED")))))));
        return new LinkedHashMap<>(Map.of("candidate", Map.of(), "verification", verification,
                "sections", List.of(Map.of("heading", "概览", "body", "正文", "source_refs", List.of("资料")))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> verification(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("verification");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> payload) {
        return (Map<String, Object>) ((List<?>) verification(payload).get("sections")).get(0);
    }

    @Test
    void acceptsBothRealCompletedAssessmentModesAndHistoricalFailedRepairRounds() {
        Map<String, Object> payload = payload();
        section(payload).put("statements", List.of(statement("SUPPORTED", "EXACT_COPY")));
        verification(payload).put("repair_rounds", List.of(
                Map.of("round", 1, "failed_checks", List.of("旧陈述不支持"), "repaired_sections", List.of("概览")),
                Map.of("round", 2, "failed_checks", List.of("旧陈述缺少指标"), "repaired_sections", List.of("概览"))));
        assertThatCode(() -> ArtifactCandidateVerificationPolicy.validate(payload, true, true)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"status,FAIL", "status,NOT_RUN", "contract_status,FAIL", "contract_status,NOT_RUN", "evidence_status,FAIL", "evidence_status,NOT_RUN"})
    void rejectsExplicitFailureEvenOnOlderCandidate(String field, String value) {
        var payload = payload();
        verification(payload).put(field, value);
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validate(payload, false, true)).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARTIAL", "UNSUPPORTED", "NOT_RUN"})
    void mandatoryEvidenceRejectsUnsupportedOrUnexecutedSection(String support) {
        var payload = payload();
        section(payload).put("support", support);
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validate(payload, true, true)).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @CsvSource({"CONTRADICTED,ASSESSED", "UNSUPPORTED,ASSESSED", "UNDETERMINED,NOT_RUN", "SUPPORTED,NOT_RUN"})
    void statementDiagnosticsCannotClaimPassForFailedOrUnexecutedJudgment(String label, String assessment) {
        var payload = payload();
        section(payload).put("statements", List.of(statement(label, assessment)));
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validate(payload, true, true)).isInstanceOf(BusinessException.class);
    }

    @Test
    void contradictoryCitationCannotBeCombinedWithSupportedClaim() {
        var payload = payload();
        var statement = statement("SUPPORTED", "ASSESSED");
        statement.put("citations", List.of(Map.of("chunk_id", "chunk-1", "label", "CONTRADICTED")));
        section(payload).put("statements", List.of(statement));
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validate(payload, true, false)).isInstanceOf(BusinessException.class);
    }

    static Stream<Object> invalidMetrics() {
        return Stream.of(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY, "0.5", true);
    }

    @ParameterizedTest
    @MethodSource("invalidMetrics")
    void rejectsInvalidMetrics(Object metric) {
        var payload = payload();
        verification(payload).put("citation_precision", metric);
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validate(payload, true, true)).isInstanceOf(BusinessException.class);
    }

    static Stream<Object> invalidCallCounts() {
        return Stream.of(1.0, 1.0f, true, "1", -1, new BigInteger("999999999999999999999999"));
    }

    @ParameterizedTest
    @MethodSource("invalidCallCounts")
    void modelCallCountsRequireActualJsonIntegers(Object count) {
        var payload = payload();
        verification(payload).put("llm_calls", count);
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validate(payload, true, true)).isInstanceOf(BusinessException.class);
    }

    @Test
    void acceptsNullMetricsOnlyWhenMandatoryStatementsHaveNoFactualDenominator() {
        var payload = payload();
        verification(payload).put("citation_recall", null);
        verification(payload).put("citation_precision", null);
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validate(payload, true, true)).isInstanceOf(BusinessException.class);
        section(payload).put("statements", List.of(statement("NON_FACTUAL", "ASSESSED")));
        assertThatCode(() -> ArtifactCandidateVerificationPolicy.validate(payload, true, true)).doesNotThrowAnyException();
        section(payload).put("statements", List.of());
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validate(payload, true, true)).isInstanceOf(BusinessException.class);
    }

    @Test
    void optionalEvidenceRetainsTruthfulWarningAndUnsupportedDiagnostics() {
        var payload = payload();
        verification(payload).put("status", "WARN");
        verification(payload).put("evidence_status", "WARN");
        section(payload).put("support", "NOT_RUN");
        section(payload).put("statements", List.of(statement("UNDETERMINED", "NOT_RUN")));
        assertThatCode(() -> ArtifactCandidateVerificationPolicy.validate(payload, true, false)).doesNotThrowAnyException();
    }

    @Test
    void oldOrFrozenVideoCandidateKeepsExistingVerificationButCurrentGenericCannotOmitSemanticFields() {
        Map<String, Object> old = Map.of("candidate", Map.of(), "verification", Map.of("status", "PASS"));
        assertThatCode(() -> ArtifactCandidateVerificationPolicy.validate(old, false, true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validate(old, true, true)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMissingCoverageAndMoreThanTwoRepairRounds() {
        var payload = payload();
        section(payload).put("heading", "其他章节");
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validate(payload, true, true)).isInstanceOf(BusinessException.class);
        section(payload).put("heading", "概览");
        verification(payload).put("repair_rounds", List.of(
                Map.of("round", 1, "failed_checks", List.of(), "repaired_sections", List.of()),
                Map.of("round", 2, "failed_checks", List.of(), "repaired_sections", List.of()),
                Map.of("round", 3, "failed_checks", List.of(), "repaired_sections", List.of())));
        assertThatThrownBy(() -> ArtifactCandidateVerificationPolicy.validate(payload, true, true)).isInstanceOf(BusinessException.class);
    }
}
