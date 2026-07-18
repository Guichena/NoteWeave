package com.noteweave.memory;

public record MemoryRuntimeReviewItemResponse(
        String revisionId,
        String memoryItemId,
        String status,
        String displayText,
        String provenanceRef
) {
}
