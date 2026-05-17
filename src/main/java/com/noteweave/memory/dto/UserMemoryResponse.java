package com.noteweave.memory.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record UserMemoryResponse(
        boolean writeEnabled,
        String summary,
        List<MemoryItemResponse> items
) {
}
