package com.noteweave.memory;

import java.util.List;

public record MemoryRuntimeRevisionResponse(
        String revisionId,
        String memoryItemId,
        String status,
        List<String> revokedMemoryItemIds
) {
    public MemoryRuntimeRevisionResponse {
        revokedMemoryItemIds = revokedMemoryItemIds == null ? List.of() : List.copyOf(revokedMemoryItemIds);
    }
}
