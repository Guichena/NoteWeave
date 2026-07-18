package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.annotation.JsonInclude;

public record RetrievalQualityGatePolicy(
        String schemaVersion,
        String policyVersion,
        String datasetVersion,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String strategyProfile,
        int minimumCaseCount,
        Thresholds thresholds
) {
    public RetrievalQualityGatePolicy {
        schemaVersion = text(schemaVersion);
        policyVersion = text(policyVersion);
        datasetVersion = text(datasetVersion);
        strategyProfile = text(strategyProfile);
    }

    public RetrievalQualityGatePolicy(
            String schemaVersion,
            String policyVersion,
            String datasetVersion,
            int minimumCaseCount,
            Thresholds thresholds
    ) {
        this(schemaVersion, policyVersion, datasetVersion, "", minimumCaseCount, thresholds);
    }

    public record Thresholds(
            double minimumMacroRecallAtK,
            double minimumMacroMrr,
            double minimumMacroNdcgAtK,
            double minimumMacroCitationPrecision,
            double minimumMacroCitationCoverage,
            double minimumRefusalAccuracy,
            double minimumMacroRecallDelta,
            double minimumMacroMrrDelta,
            double minimumMacroNdcgDelta,
            double minimumMacroCitationPrecisionDelta,
            double minimumMacroCitationCoverageDelta,
            double minimumRefusalAccuracyDelta,
            double minimumMeanTopKOverlap,
            double maximumTop1ChangedRatio,
            long maximumScopeViolationCount,
            long maximumP95LatencyMicros
    ) {
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
