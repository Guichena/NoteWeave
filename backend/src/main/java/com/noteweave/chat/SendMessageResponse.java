package com.noteweave.chat;

import java.util.List;

public record SendMessageResponse(
        String messageId,
        String assistantMessageId,
        String assistantRequestId,
        String streamUrl,
        String answerRunId,
        String answerStreamUrl,
        boolean retrievalDegraded,
        List<String> retrievalDegradationReasons
) {
    public SendMessageResponse {
        retrievalDegradationReasons = retrievalDegradationReasons == null
                ? List.of() : List.copyOf(retrievalDegradationReasons);
    }
}
