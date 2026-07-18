package com.noteweave.answer;

import java.time.Instant;
import java.util.List;

public record AnswerRunResponse(
        String id,
        String workspaceId,
        String conversationId,
        String mode,
        String status,
        String queryMessageId,
        String answerMessageId,
        String assistantRequestId,
        String retrievalPlanVersion,
        String model,
        Integer maximumOutputTokens,
        Boolean retrievalDegraded,
        List<String> retrievalDegradationReasons,
        Integer revisionNo,
        String revisionStatus,
        String content,
        Instant startedAt,
        Instant firstTokenAt,
        Instant finishedAt,
        String errorCode,
        String errorMessage
) {
    public AnswerRunResponse {
        retrievalDegradationReasons = retrievalDegradationReasons == null
                ? List.of() : List.copyOf(retrievalDegradationReasons);
    }
}
