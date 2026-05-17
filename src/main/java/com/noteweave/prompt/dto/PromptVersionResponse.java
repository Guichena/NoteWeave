package com.noteweave.prompt.dto;

import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record PromptVersionResponse(
        Long id,
        String name,
        String scene,
        Integer version,
        String content,
        String variablesJson,
        String status,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
