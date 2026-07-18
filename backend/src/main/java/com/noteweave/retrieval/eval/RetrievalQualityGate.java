package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.eval.RetrievalQualityGatePolicy.Thresholds;
import com.noteweave.retrieval.eval.RetrievalShadowComparator.ShadowComparisonReport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RetrievalQualityGate {
    public static final String POLICY_SCHEMA_VERSION = "retrieval-quality-gate-v1";
    private static final double EPSILON = 1.0e-12d;

    private final ObjectMapper objectMapper;
    private final RetrievalShadowComparator comparator;

    public RetrievalQualityGate() {
        this(new ObjectMapper().findAndRegisterModules(), new RetrievalShadowComparator());
    }

    RetrievalQualityGate(ObjectMapper objectMapper, RetrievalShadowComparator comparator) {
        this.objectMapper = objectMapper;
        this.comparator = comparator;
    }

    public QualityGateResult evaluate(
            Path goldSetPath,
            Path shadowSnapshotPath,
            Path policyPath
    ) throws Exception {
        RetrievalQualityGatePolicy policy = objectMapper.readValue(
                policyPath.toFile(), RetrievalQualityGatePolicy.class);
        return evaluate(comparator.compare(goldSetPath, shadowSnapshotPath), policy);
    }

    public QualityGateResult evaluate(
            ShadowComparisonReport comparison,
            RetrievalQualityGatePolicy policy
    ) {
        validatePolicy(policy);
        if (!policy.datasetVersion().equals(comparison.datasetVersion())) {
            throw new IllegalArgumentException(
                    "Quality gate dataset does not match comparison: policy="
                            + policy.datasetVersion() + ", comparison=" + comparison.datasetVersion());
        }
        if (!policy.strategyProfile().equals(comparison.strategyProfile())) {
            throw new IllegalArgumentException(
                    "Quality gate strategy profile does not match comparison: policy="
                            + displayProfile(policy.strategyProfile())
                            + ", comparison=" + displayProfile(comparison.strategyProfile()));
        }

        var overall = comparison.shadow().overall();
        var summary = comparison.summary();
        double top1ChangedRatio = summary.caseCount() == 0
                ? 0.0d
                : summary.top1ChangedCount() / (double) summary.caseCount();
        GateMetrics metrics = new GateMetrics(
                summary.caseCount(),
                overall.macroRecallAtK(),
                overall.macroMrr(),
                overall.macroNdcgAtK(),
                overall.macroCitationPrecision(),
                overall.macroCitationCoverage(),
                overall.refusalAccuracy(),
                summary.macroRecallDelta(),
                summary.macroMrrDelta(),
                summary.macroNdcgDelta(),
                summary.macroCitationPrecisionDelta(),
                summary.macroCitationCoverageDelta(),
                summary.refusalAccuracyDelta(),
                summary.meanTopKOverlap(),
                top1ChangedRatio,
                summary.scopeViolationCount(),
                summary.p95LatencyMicros()
        );

        Thresholds thresholds = policy.thresholds();
        List<GateViolation> violations = new ArrayList<>();
        minimum("caseCount", metrics.caseCount(), policy.minimumCaseCount(), violations);
        minimum("macroRecallAtK", metrics.macroRecallAtK(), thresholds.minimumMacroRecallAtK(), violations);
        minimum("macroMrr", metrics.macroMrr(), thresholds.minimumMacroMrr(), violations);
        minimum("macroNdcgAtK", metrics.macroNdcgAtK(), thresholds.minimumMacroNdcgAtK(), violations);
        minimum(
                "macroCitationPrecision",
                metrics.macroCitationPrecision(),
                thresholds.minimumMacroCitationPrecision(),
                violations);
        minimum(
                "macroCitationCoverage",
                metrics.macroCitationCoverage(),
                thresholds.minimumMacroCitationCoverage(),
                violations);
        minimum(
                "refusalAccuracy",
                metrics.refusalAccuracy(),
                thresholds.minimumRefusalAccuracy(),
                violations);
        minimum(
                "macroRecallDelta",
                metrics.macroRecallDelta(),
                thresholds.minimumMacroRecallDelta(),
                violations);
        minimum("macroMrrDelta", metrics.macroMrrDelta(), thresholds.minimumMacroMrrDelta(), violations);
        minimum(
                "macroNdcgDelta",
                metrics.macroNdcgDelta(),
                thresholds.minimumMacroNdcgDelta(),
                violations);
        minimum(
                "macroCitationPrecisionDelta",
                metrics.macroCitationPrecisionDelta(),
                thresholds.minimumMacroCitationPrecisionDelta(),
                violations);
        minimum(
                "macroCitationCoverageDelta",
                metrics.macroCitationCoverageDelta(),
                thresholds.minimumMacroCitationCoverageDelta(),
                violations);
        minimum(
                "refusalAccuracyDelta",
                metrics.refusalAccuracyDelta(),
                thresholds.minimumRefusalAccuracyDelta(),
                violations);
        minimum(
                "meanTopKOverlap",
                metrics.meanTopKOverlap(),
                thresholds.minimumMeanTopKOverlap(),
                violations);
        maximum(
                "top1ChangedRatio",
                metrics.top1ChangedRatio(),
                thresholds.maximumTop1ChangedRatio(),
                violations);
        maximum(
                "scopeViolationCount",
                metrics.scopeViolationCount(),
                thresholds.maximumScopeViolationCount(),
                violations);
        maximum(
                "p95LatencyMicros",
                metrics.p95LatencyMicros(),
                thresholds.maximumP95LatencyMicros(),
                violations);

        return new QualityGateResult(
                policy.policyVersion(),
                comparison.datasetVersion(),
                comparison.shadowSnapshotVersion(),
                comparison.strategyProfile(),
                violations.isEmpty(),
                metrics,
                List.copyOf(violations)
        );
    }

    private void validatePolicy(RetrievalQualityGatePolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Retrieval quality gate policy is required");
        }
        if (!POLICY_SCHEMA_VERSION.equals(policy.schemaVersion())) {
            throw new IllegalArgumentException(
                    "Unsupported retrieval quality gate schema: " + policy.schemaVersion());
        }
        if (policy.policyVersion().isBlank() || policy.datasetVersion().isBlank()) {
            throw new IllegalArgumentException("Quality gate policyVersion and datasetVersion are required");
        }
        if (policy.minimumCaseCount() <= 0 || policy.thresholds() == null) {
            throw new IllegalArgumentException("Quality gate minimumCaseCount and thresholds are required");
        }
        Thresholds thresholds = policy.thresholds();
        unit("minimumMacroRecallAtK", thresholds.minimumMacroRecallAtK());
        unit("minimumMacroMrr", thresholds.minimumMacroMrr());
        unit("minimumMacroNdcgAtK", thresholds.minimumMacroNdcgAtK());
        unit("minimumMacroCitationPrecision", thresholds.minimumMacroCitationPrecision());
        unit("minimumMacroCitationCoverage", thresholds.minimumMacroCitationCoverage());
        unit("minimumRefusalAccuracy", thresholds.minimumRefusalAccuracy());
        delta("minimumMacroRecallDelta", thresholds.minimumMacroRecallDelta());
        delta("minimumMacroMrrDelta", thresholds.minimumMacroMrrDelta());
        delta("minimumMacroNdcgDelta", thresholds.minimumMacroNdcgDelta());
        delta("minimumMacroCitationPrecisionDelta", thresholds.minimumMacroCitationPrecisionDelta());
        delta("minimumMacroCitationCoverageDelta", thresholds.minimumMacroCitationCoverageDelta());
        delta("minimumRefusalAccuracyDelta", thresholds.minimumRefusalAccuracyDelta());
        unit("minimumMeanTopKOverlap", thresholds.minimumMeanTopKOverlap());
        unit("maximumTop1ChangedRatio", thresholds.maximumTop1ChangedRatio());
        if (thresholds.maximumScopeViolationCount() < 0 || thresholds.maximumP95LatencyMicros() < 0) {
            throw new IllegalArgumentException("Quality gate maximum counts and latency must be non-negative");
        }
    }

    private void unit(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }

    private void delta(String name, double value) {
        if (!Double.isFinite(value) || value < -1.0d || value > 1.0d) {
            throw new IllegalArgumentException(name + " must be between -1 and 1");
        }
    }

    private String displayProfile(String strategyProfile) {
        return strategyProfile.isEmpty() ? "<generic>" : strategyProfile;
    }

    private void minimum(String metric, double actual, double threshold, List<GateViolation> violations) {
        if (actual + EPSILON < threshold) {
            violations.add(new GateViolation(metric, ">=", threshold, actual));
        }
    }

    private void maximum(String metric, double actual, double threshold, List<GateViolation> violations) {
        if (actual - EPSILON > threshold) {
            violations.add(new GateViolation(metric, "<=", threshold, actual));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: RetrievalQualityGate <gold-set.json> <shadow-snapshot.json> <gate-policy.json>");
        }
        RetrievalQualityGate gate = new RetrievalQualityGate();
        QualityGateResult result = gate.evaluate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        System.out.println(gate.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        if (!result.passed()) {
            throw new IllegalStateException(
                    "Retrieval quality gate failed with " + result.violations().size() + " violation(s)");
        }
    }

    public record QualityGateResult(
            String policyVersion,
            String datasetVersion,
            String shadowSnapshotVersion,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) String strategyProfile,
            boolean passed,
            GateMetrics metrics,
            List<GateViolation> violations
    ) {
        public QualityGateResult {
            strategyProfile = strategyProfile == null ? "" : strategyProfile.trim();
        }

        public QualityGateResult(
                String policyVersion,
                String datasetVersion,
                String shadowSnapshotVersion,
                boolean passed,
                GateMetrics metrics,
                List<GateViolation> violations
        ) {
            this(policyVersion, datasetVersion, shadowSnapshotVersion, "", passed, metrics, violations);
        }
    }

    public record GateMetrics(
            int caseCount,
            double macroRecallAtK,
            double macroMrr,
            double macroNdcgAtK,
            double macroCitationPrecision,
            double macroCitationCoverage,
            double refusalAccuracy,
            double macroRecallDelta,
            double macroMrrDelta,
            double macroNdcgDelta,
            double macroCitationPrecisionDelta,
            double macroCitationCoverageDelta,
            double refusalAccuracyDelta,
            double meanTopKOverlap,
            double top1ChangedRatio,
            long scopeViolationCount,
            long p95LatencyMicros
    ) {
    }

    public record GateViolation(
            String metric,
            String operator,
            double threshold,
            double actual
    ) {
    }
}
