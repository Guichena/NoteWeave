package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryCandidatePolicyTest {

    private final MemoryCandidatePolicy policy = new MemoryCandidatePolicy();
    private final MemoryCandidateGate gate = new MemoryCandidateGate(policy);
    private final MemoryStatementMatcher matcher = new MemoryStatementMatcher(policy);

    @Test
    void shouldExposeVersionedConfidenceAndUtilityPolicy() {
        assertThat(policy.version()).isEqualTo("memory-candidate-policy-v1");
        assertThat(policy.confidenceForSource("USER_FEEDBACK")).isEqualTo(0.98);
        assertThat(policy.confidenceForSource("MODEL_INFERENCE")).isEqualTo(0.35);
        assertThat(policy.marginalUtility(
                "USER_FEEDBACK", "NEGATIVE", "COMMON")).isCloseTo(0.98, within(0.0001));
    }

    @Test
    void shouldHoldWeakInferenceButAllowLowRiskExplicitNegativePreference() {
        MemoryCandidateGate.GateDecision weak = gate.evaluate(
                signal("MODEL_INFERENCE", "PREFERENCE", "CHAT", 0.35),
                policy.marginalUtility("MODEL_INFERENCE", "PREFERENCE", "CHAT"),
                "NO_CONFLICT");
        assertThat(weak.evidenceGateStatus()).isEqualTo("NEEDS_REVIEW");
        assertThat(weak.reviewStatus()).isEqualTo("NEEDS_REVIEW");
        assertThat(weak.scopeStatus()).isEqualTo("VALID");

        MemoryCandidateGate.GateDecision explicitNegative = gate.evaluate(
                signal("USER_FEEDBACK", "NEGATIVE", "CHAT_QA", 0.98),
                policy.marginalUtility("USER_FEEDBACK", "NEGATIVE", "CHAT_QA"),
                "NO_CONFLICT");
        assertThat(explicitNegative.evidenceGateStatus()).isEqualTo("PASS");
        assertThat(explicitNegative.riskScore()).isLessThan(policy.reviewRequiredRisk());
        assertThat(explicitNegative.reviewStatus()).isEqualTo("READY");
    }

    @Test
    void shouldRequireReviewForConflictingActiveMemory() {
        MemoryCandidateGate.GateDecision decision = gate.evaluate(
                signal("USER_FEEDBACK", "PREFERENCE", "CHAT_QA", 0.98),
                policy.marginalUtility("USER_FEEDBACK", "PREFERENCE", "CHAT_QA"),
                "CONFLICTING_ACTIVE_MEMORY");

        assertThat(decision.riskScore()).isGreaterThanOrEqualTo(
                policy.reviewRequiredRisk());
        assertThat(decision.reviewStatus()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void shouldMatchEquivalentStatementsAfterRuleNormalization() {
        String canonical = "正式文档统一使用先结论后结构";
        String punctuationVariant = "正式文档，统一使用先结论后结构。";

        assertThat(matcher.similarity(canonical, punctuationVariant)).isEqualTo(1.0);
        assertThat(matcher.equivalent(canonical, punctuationVariant)).isTrue();
        assertThat(matcher.equivalent(canonical, "正式文档统一使用附录优先结构"))
                .isFalse();
    }

    private MemorySignalService.SignalRow signal(
            String sourceType,
            String signalType,
            String taskNeighborhood,
            double confidence
    ) {
        return new MemorySignalService.SignalRow(
                "signal",
                "workspace",
                "user",
                sourceType,
                null,
                signalType,
                "statement",
                taskNeighborhood,
                new MemorySignalService.MemoryCompileHints(
                        List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of()),
                confidence,
                policy.version());
    }
}
