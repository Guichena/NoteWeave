package com.noteweave.answer.strategy;

import java.util.List;

public record RetrievalExecutionTrace(
        String schemaVersion,
        String planVersion,
        long totalLatencyMicros,
        int rawCandidateCount,
        int admittedCandidateCount,
        int selectedEvidenceCount,
        int selectedEvidenceCharacters,
        List<StepTrace> steps,
        List<SelectedEvidenceTrace> selectedEvidence
) {
    public static final String SCHEMA_VERSION = "retrieval-execution-trace-v1";

    public RetrievalExecutionTrace {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion;
        planVersion = planVersion == null ? "" : planVersion;
        totalLatencyMicros = Math.max(0, totalLatencyMicros);
        rawCandidateCount = Math.max(0, rawCandidateCount);
        admittedCandidateCount = Math.max(0, admittedCandidateCount);
        selectedEvidenceCount = Math.max(0, selectedEvidenceCount);
        selectedEvidenceCharacters = Math.max(0, selectedEvidenceCharacters);
        steps = steps == null ? List.of() : List.copyOf(steps);
        selectedEvidence = selectedEvidence == null ? List.of() : List.copyOf(selectedEvidence);
    }

    public static RetrievalExecutionTrace empty(String planVersion) {
        return new RetrievalExecutionTrace(
                SCHEMA_VERSION, planVersion, 0, 0, 0, 0, 0, List.of(), List.of());
    }

    public record StepTrace(
            int stepIndex,
            String channel,
            int candidateLimit,
            int rawCandidateCount,
            int admittedCandidateCount,
            long latencyMicros,
            boolean degraded,
            List<String> degradationReasons,
            java.util.Map<String, Long> measurements
    ) {
        public StepTrace {
            channel = channel == null ? "" : channel;
            candidateLimit = Math.max(0, candidateLimit);
            rawCandidateCount = Math.max(0, rawCandidateCount);
            admittedCandidateCount = Math.max(0, admittedCandidateCount);
            latencyMicros = Math.max(0, latencyMicros);
            degradationReasons = degradationReasons == null
                    ? List.of() : List.copyOf(degradationReasons);
            measurements = measurements == null
                    ? java.util.Map.of() : java.util.Map.copyOf(measurements);
        }
    }

    public record SelectedEvidenceTrace(
            int rank,
            String evidenceId,
            String kind,
            double rawScore,
            double fusedScore,
            double rerankScore,
            int characterCost
    ) {
        public SelectedEvidenceTrace {
            rank = Math.max(1, rank);
            evidenceId = evidenceId == null ? "" : evidenceId;
            kind = kind == null ? "" : kind;
            characterCost = Math.max(0, characterCost);
        }
    }
}
