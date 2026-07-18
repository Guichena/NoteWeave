package com.noteweave.answer;

public record AnswerRunRef(
        String runId,
        String workspaceId,
        String conversationId,
        String answerMessageId,
        String assistantRequestId,
        String status
) {
}
