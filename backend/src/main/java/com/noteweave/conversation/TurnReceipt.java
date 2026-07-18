package com.noteweave.conversation;

import com.noteweave.chat.SendMessageResponse;
import com.noteweave.research.ResearchRunResponse;
import java.util.List;

public record TurnReceipt(
        String submissionId,
        String executionKind,
        String messageId,
        String assistantMessageId,
        String assistantRequestId,
        String streamUrl,
        String answerRunId,
        String researchRunId,
        String answerStreamUrl,
        boolean retrievalDegraded,
        List<String> retrievalDegradationReasons,
        boolean reused
) {
    public TurnReceipt {
        retrievalDegradationReasons = retrievalDegradationReasons == null
                ? List.of() : List.copyOf(retrievalDegradationReasons);
    }

    static TurnReceipt answer(String submissionId, SendMessageResponse response) {
        return new TurnReceipt(
                submissionId,
                "ANSWER",
                response.messageId(),
                response.assistantMessageId(),
                response.assistantRequestId(),
                response.streamUrl(),
                response.answerRunId(),
                null,
                response.answerStreamUrl(),
                response.retrievalDegraded(),
                response.retrievalDegradationReasons(),
                false
        );
    }

    static TurnReceipt answerPreparing(
            String submissionId,
            String workspaceId,
            String messageId,
            String assistantMessageId,
            String assistantRequestId,
            String answerRunId
    ) {
        return new TurnReceipt(
                submissionId,
                "ANSWER",
                messageId,
                assistantMessageId,
                assistantRequestId,
                "/api/v2/chat/requests/" + assistantRequestId + "/stream",
                answerRunId,
                null,
                "/api/v2/workspaces/" + workspaceId + "/answer-runs/" + answerRunId + "/events",
                false,
                List.of(),
                false
        );
    }

    static TurnReceipt research(
            String submissionId,
            String messageId,
            String assistantMessageId,
            ResearchRunResponse response
    ) {
        return new TurnReceipt(
                submissionId,
                "RESEARCH",
                messageId,
                assistantMessageId,
                null,
                null,
                null,
                response.researchRunId(),
                null,
                false,
                List.of(),
                false
        );
    }

    TurnReceipt asReused() {
        return new TurnReceipt(
                submissionId, executionKind, messageId, assistantMessageId,
                assistantRequestId, streamUrl, answerRunId, researchRunId,
                answerStreamUrl, retrievalDegraded, retrievalDegradationReasons, true
        );
    }
}
