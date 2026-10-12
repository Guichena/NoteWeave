package com.noteweave.memory;

import java.util.List;

public record MemoryCompilationTraceResponse(
        String policyVersion,
        int maximumTokens,
        int selectedTokens,
        int candidateCount,
        int selectedCount,
        int droppedCount,
        boolean truncated,
        boolean degraded,
        List<String> degradationReasons,
        List<Decision> decisions
) {
    public MemoryCompilationTraceResponse {
        degradationReasons = degradationReasons == null
                ? List.of() : List.copyOf(degradationReasons);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    public MemoryCompilationTraceResponse(
            String policyVersion,
            int maximumTokens,
            int selectedTokens,
            int candidateCount,
            int selectedCount,
            int droppedCount,
            boolean truncated,
            boolean degraded,
            List<String> degradationReasons
    ) {
        this(policyVersion, maximumTokens, selectedTokens, candidateCount, selectedCount, droppedCount,
                truncated, degraded, degradationReasons, List.of());
    }

    public static MemoryCompilationTraceResponse empty() {
        return new MemoryCompilationTraceResponse(
                "legacy-unbounded",
                0,
                0,
                0,
                0,
                0,
                false,
                false,
                List.of()
        );
    }

    public record Decision(String memoryObjectId, boolean selected, String reason) {
    }
}
