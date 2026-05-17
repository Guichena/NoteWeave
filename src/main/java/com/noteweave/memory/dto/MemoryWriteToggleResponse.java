package com.noteweave.memory.dto;

import lombok.Builder;

@Builder
public record MemoryWriteToggleResponse(
        boolean writeEnabled
) {
}
