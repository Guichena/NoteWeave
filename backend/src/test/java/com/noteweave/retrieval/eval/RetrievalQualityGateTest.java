package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.eval.RetrievalQualityGatePolicy.Thresholds;
import com.noteweave.retrieval.eval.RetrievalShadowSnapshot.CaseRanking;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalQualityGateTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldFailControlledDriftAndReportDeterministicViolations() throws Exception {
        Path goldSet = resource("stage5-retrieval-gold-v1.json");
        Path shadow = resource("stage5-shadow-fixture-v1.json");
        Path policy = resource("stage5-quality-gate-policy-v1.json");

        var result = new RetrievalQualityGate().evaluate(goldSet, shadow, policy);

        assertThat(result.passed()).isFalse();
        assertThat(result.policyVersion()).isEqualTo("stage5-fixture-policy-v1");
        assertThat(result.strategyProfile()).isEmpty();
        assertThat(result.metrics().caseCount()).isEqualTo(4);
        assertThat(result.violations()).extracting(item -> item.metric())
                .contains(
                        "macroRecallAtK",
                        "macroMrr",
                        "macroNdcgAtK",
                        "macroCitationPrecision",
                        "macroCitationCoverage",
                        "refusalAccuracy",
                        "macroRecallDelta",
                        "macroMrrDelta",
                        "macroNdcgDelta",
                        "macroCitationPrecisionDelta",
                        "macroCitationCoverageDelta",
                        "refusalAccuracyDelta",
                        "meanTopKOverlap",
                        "top1ChangedRatio",
                        "scopeViolationCount",
                        "p95LatencyMicros");
    }

    @Test
    void shouldPassBaselineEquivalentShadowWithinVersionedPolicy() throws Exception {
        RetrievalGoldSet goldSet = objectMapper.readValue(
                resource("stage5-retrieval-gold-v1.json").toFile(), RetrievalGoldSet.class);
        RetrievalQualityGatePolicy policy = objectMapper.readValue(
                resource("stage5-quality-gate-policy-v1.json").toFile(),
                RetrievalQualityGatePolicy.class);
        DeterministicBm25Baseline baseline = new DeterministicBm25Baseline();
        List<CaseRanking> rankings = goldSet.cases().stream().map(goldCase -> {
            var ranked = baseline.rank(goldCase);
            return new CaseRanking(goldCase.id(), 900L, ranked.size(), ranked);
        }).toList();
        var comparison = new RetrievalShadowComparator().compare(
                goldSet,
                new RetrievalShadowSnapshot(
                        RetrievalShadowComparator.SHADOW_SCHEMA_VERSION,
                        "baseline-equivalent-shadow",
                        rankings));

        var result = new RetrievalQualityGate().evaluate(comparison, policy);

        assertThat(result.passed()).isTrue();
        assertThat(result.strategyProfile()).isEmpty();
        assertThat(result.violations()).isEmpty();
        assertThat(result.metrics().macroRecallAtK()).isEqualTo(1.0d);
        assertThat(result.metrics().meanTopKOverlap()).isEqualTo(1.0d);
        assertThat(result.metrics().top1ChangedRatio()).isZero();
        assertThat(result.metrics().scopeViolationCount()).isZero();
        assertThat(result.metrics().p95LatencyMicros()).isEqualTo(900L);
    }

    @Test
    void shouldRequireTheExactV2StrategyProfileForProfiledShadow() throws Exception {
        RetrievalGoldSet goldSet = objectMapper.readValue(
                resource("stage5-retrieval-gold-v1.json").toFile(), RetrievalGoldSet.class);
        RetrievalQualityGatePolicy genericPolicy = objectMapper.readValue(
                resource("stage5-quality-gate-policy-v1.json").toFile(),
                RetrievalQualityGatePolicy.class);
        DeterministicBm25Baseline baseline = new DeterministicBm25Baseline();
        var snapshot = new RetrievalShadowSnapshot(
                RetrievalShadowComparator.SHADOW_SCHEMA_VERSION,
                "v2-profile-shadow",
                "qa-retrieval-v2",
                goldSet.cases().stream().map(goldCase -> {
                    var ranked = baseline.rank(goldCase);
                    return new CaseRanking(goldCase.id(), 1L, ranked.size(), ranked);
                }).toList());
        var comparison = new RetrievalShadowComparator().compare(goldSet, snapshot);
        var v2Policy = withStrategyProfile(genericPolicy, "qa-retrieval-v2");
        var legacyPolicy = withStrategyProfile(genericPolicy, "qa-retrieval-legacy-v1");

        var result = new RetrievalQualityGate().evaluate(comparison, v2Policy);

        assertThat(result.passed()).isTrue();
        assertThat(result.strategyProfile()).isEqualTo("qa-retrieval-v2");
        assertThatThrownBy(() -> new RetrievalQualityGate().evaluate(comparison, legacyPolicy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy profile does not match")
                .hasMessageContaining("policy=qa-retrieval-legacy-v1")
                .hasMessageContaining("comparison=qa-retrieval-v2");
        assertThatThrownBy(() -> new RetrievalQualityGate().evaluate(comparison, genericPolicy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policy=<generic>")
                .hasMessageContaining("comparison=qa-retrieval-v2");
    }

    @Test
    void shouldRejectMismatchedDatasetAndInvalidThresholdRange() throws Exception {
        RetrievalGoldSet goldSet = objectMapper.readValue(
                resource("stage5-retrieval-gold-v1.json").toFile(), RetrievalGoldSet.class);
        RetrievalQualityGatePolicy policy = objectMapper.readValue(
                resource("stage5-quality-gate-policy-v1.json").toFile(),
                RetrievalQualityGatePolicy.class);
        DeterministicBm25Baseline baseline = new DeterministicBm25Baseline();
        var snapshot = new RetrievalShadowSnapshot(
                RetrievalShadowComparator.SHADOW_SCHEMA_VERSION,
                "validation-shadow",
                goldSet.cases().stream().map(goldCase -> {
                    var ranked = baseline.rank(goldCase);
                    return new CaseRanking(goldCase.id(), 1L, ranked.size(), ranked);
                }).toList());
        var comparison = new RetrievalShadowComparator().compare(goldSet, snapshot);

        var mismatched = new RetrievalQualityGatePolicy(
                policy.schemaVersion(), policy.policyVersion(), "another-dataset",
                policy.minimumCaseCount(), policy.thresholds());
        assertThatThrownBy(() -> new RetrievalQualityGate().evaluate(comparison, mismatched))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataset does not match");

        Thresholds thresholds = policy.thresholds();
        var invalid = new RetrievalQualityGatePolicy(
                policy.schemaVersion(), policy.policyVersion(), policy.datasetVersion(),
                policy.minimumCaseCount(),
                new Thresholds(
                        1.01d,
                        thresholds.minimumMacroMrr(),
                        thresholds.minimumMacroNdcgAtK(),
                        thresholds.minimumMacroCitationPrecision(),
                        thresholds.minimumMacroCitationCoverage(),
                        thresholds.minimumRefusalAccuracy(),
                        thresholds.minimumMacroRecallDelta(),
                        thresholds.minimumMacroMrrDelta(),
                        thresholds.minimumMacroNdcgDelta(),
                        thresholds.minimumMacroCitationPrecisionDelta(),
                        thresholds.minimumMacroCitationCoverageDelta(),
                        thresholds.minimumRefusalAccuracyDelta(),
                        thresholds.minimumMeanTopKOverlap(),
                        thresholds.maximumTop1ChangedRatio(),
                        thresholds.maximumScopeViolationCount(),
                        thresholds.maximumP95LatencyMicros()));
        assertThatThrownBy(() -> new RetrievalQualityGate().evaluate(comparison, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumMacroRecallAtK");
    }

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getResource("/retrieval/" + name).toURI());
    }

    private RetrievalQualityGatePolicy withStrategyProfile(
            RetrievalQualityGatePolicy policy,
            String strategyProfile
    ) {
        return new RetrievalQualityGatePolicy(
                policy.schemaVersion(),
                policy.policyVersion(),
                policy.datasetVersion(),
                strategyProfile,
                policy.minimumCaseCount(),
                policy.thresholds());
    }
}
