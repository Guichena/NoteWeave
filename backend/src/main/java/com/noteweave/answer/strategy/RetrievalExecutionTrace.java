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
            java.util.Map<String, Long> measurements,
            List<RankedCandidateTrace> rankedCandidates
    ) {
        public StepTrace(
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
            this(stepIndex, channel, candidateLimit, rawCandidateCount, admittedCandidateCount,
                    latencyMicros, degraded, degradationReasons, measurements, List.of());
        }

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
            rankedCandidates = rankedCandidates == null ? List.of() : List.copyOf(rankedCandidates);
        }
    }

    /**
     * 证据挑选前的候选排序（重排后的前若干条），用于离线评测检索排序本身的质量，
     * 与最终进入上下文的证据分开计算。excerpt 只保留命中段落正文，不含相邻段落。
     */
    public record RankedCandidateTrace(
            int rank,
            String evidenceId,
            String sourceId,
            int chunkNo,
            double score,
            boolean selected,
            String excerpt
    ) {
        public RankedCandidateTrace {
            rank = Math.max(1, rank);
            evidenceId = evidenceId == null ? "" : evidenceId;
            sourceId = sourceId == null ? "" : sourceId;
            excerpt = excerpt == null ? "" : excerpt;
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
