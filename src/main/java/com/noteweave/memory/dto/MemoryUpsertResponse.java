package com.noteweave.memory.dto;

import lombok.Builder;

@Builder
public record MemoryUpsertResponse(
        boolean writeEnabled,
        String summary,
        MemoryItemResponse item
) {
}
