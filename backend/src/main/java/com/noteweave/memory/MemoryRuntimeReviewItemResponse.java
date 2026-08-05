package com.noteweave.memory;

public record MemoryRuntimeReviewItemResponse(
        String revisionId,
        String memoryItemId,
        String reviewKind,
        String status,
        String displayText,
        String provenanceRef,
        String conflictStatus,
        double utilityScore,
        String reviewStatus,
        String lifecycleStatus
) {
}
