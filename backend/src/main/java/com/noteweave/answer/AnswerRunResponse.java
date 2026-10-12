package com.noteweave.answer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.noteweave.retrieval.QueryRewrite;
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
        String errorMessage,
        @JsonInclude(JsonInclude.Include.NON_NULL) QueryRewrite.View retrievalRewrite
) {
    public AnswerRunResponse {
        retrievalDegradationReasons = retrievalDegradationReasons == null
                ? List.of() : List.copyOf(retrievalDegradationReasons);
    }
}
