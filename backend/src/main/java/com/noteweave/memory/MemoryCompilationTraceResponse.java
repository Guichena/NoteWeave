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
        List<String> degradationReasons
) {
    public MemoryCompilationTraceResponse {
        degradationReasons = degradationReasons == null
                ? List.of() : List.copyOf(degradationReasons);
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
}
