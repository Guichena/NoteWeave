package com.noteweave.conversation;

import java.time.Instant;

public record TurnSubmissionResponse(
        String submissionId,
        String clientRequestId,
        String executionKind,
        String status,
        int preparationAttempt,
        String queryMessageId,
        String answerMessageId,
        String answerRunId,
        String researchRunId,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
