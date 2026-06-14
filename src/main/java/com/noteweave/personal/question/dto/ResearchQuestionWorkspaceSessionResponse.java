package com.noteweave.personal.question.dto;

import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record ResearchQuestionWorkspaceSessionResponse(
        Long sessionSummaryId,
        Long sessionId,
        String topic,
        String summary,
        LocalDateTime updatedAt
) {
}
