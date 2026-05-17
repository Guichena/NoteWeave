package com.noteweave.rageval.dto;

import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record RagEvalCaseResponse(
        Long id,
        Long spaceId,
        String name,
        String queryText,
        String expectedAnswer,
        String expectedSourceJson,
        String tagsJson,
        boolean enabled,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
