package com.noteweave.memory;

public record MemoryRuntimeRevisionResponse(
        String revisionId,
        String memoryItemId,
        String status
) {
}
