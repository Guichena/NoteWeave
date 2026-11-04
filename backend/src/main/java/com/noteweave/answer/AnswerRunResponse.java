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
        @JsonInclude(JsonInclude.Include.NON_NULL) QueryRewrite.View retrievalRewrite,
        @JsonInclude(JsonInclude.Include.NON_NULL) String evidenceSufficiency,
        @com.fasterxml.jackson.annotation.JsonProperty("citation_protocol") EvidenceReadyEvent citationProtocol,
        @com.fasterxml.jackson.annotation.JsonProperty("event_sequence") long eventSequence
) {
    public AnswerRunResponse(
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
            QueryRewrite.View retrievalRewrite
    ) {
        this(id, workspaceId, conversationId, mode, status, queryMessageId, answerMessageId,
                assistantRequestId, retrievalPlanVersion, model, maximumOutputTokens, retrievalDegraded,
                retrievalDegradationReasons, revisionNo, revisionStatus, content, startedAt, firstTokenAt,
                finishedAt, errorCode, errorMessage, retrievalRewrite, null);
    }

    public AnswerRunResponse(String id, String workspaceId, String conversationId, String mode,
            String status, String queryMessageId, String answerMessageId, String assistantRequestId,
            String retrievalPlanVersion, String model, Integer maximumOutputTokens,
            Boolean retrievalDegraded, List<String> retrievalDegradationReasons, Integer revisionNo,
            String revisionStatus, String content, Instant startedAt, Instant firstTokenAt,
            Instant finishedAt, String errorCode, String errorMessage, QueryRewrite.View retrievalRewrite,
            String evidenceSufficiency) {
        this(id, workspaceId, conversationId, mode, status, queryMessageId, answerMessageId,
                assistantRequestId, retrievalPlanVersion, model, maximumOutputTokens, retrievalDegraded,
                retrievalDegradationReasons, revisionNo, revisionStatus, content, startedAt, firstTokenAt,
                finishedAt, errorCode, errorMessage, retrievalRewrite, evidenceSufficiency,
                EvidenceReadyEvent.unknown(), 0L);
    }

    /** 回答关联的消息（回答本身或它的提问）已被删除时的错误码。 */
    public static final String MESSAGE_DELETED_ERROR_CODE = "ANSWER_MESSAGE_DELETED";

    /**
     * 显式的删除标记：回答或它的提问已被删除时为 true。
     * 调用方不必再从 CANCELLED 状态和错误码里推断这条回答是被停止还是被删除。
     */
    @com.fasterxml.jackson.annotation.JsonProperty("deleted")
    public boolean deleted() {
        return MESSAGE_DELETED_ERROR_CODE.equals(errorCode);
    }

    /** 回答已停止生成（用户停止或随删除一起停止）时为 true。 */
    @com.fasterxml.jackson.annotation.JsonProperty("cancelled")
    public boolean cancelled() {
        return "CANCELLED".equals(status);
    }

    public AnswerRunResponse {
        retrievalDegradationReasons = retrievalDegradationReasons == null
                ? List.of() : List.copyOf(retrievalDegradationReasons);
        evidenceSufficiency = EvidenceSufficiency.canonical(evidenceSufficiency);
    }
}
