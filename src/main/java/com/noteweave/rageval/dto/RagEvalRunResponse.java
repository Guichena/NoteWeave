package com.noteweave.rageval.dto;

import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record RagEvalRunResponse(
        Long id,
        Long spaceId,
        String name,
        String status,
        Integer caseCount,
        Long startedBy,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String summaryJson,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
