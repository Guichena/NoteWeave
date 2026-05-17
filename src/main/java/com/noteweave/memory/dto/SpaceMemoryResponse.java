package com.noteweave.memory.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record SpaceMemoryResponse(
        Long spaceId,
        boolean writeEnabled,
        String summary,
        List<MemoryItemResponse> items
) {
}
