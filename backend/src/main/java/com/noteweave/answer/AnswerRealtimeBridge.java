package com.noteweave.answer;

import java.time.Duration;
import java.util.List;

/** Short-lived cross-instance transport. MySQL remains the business truth. */
public interface AnswerRealtimeBridge {

    void publish(String runId, AnswerLiveEvent event);

    List<AnswerLiveEvent> readAfter(String runId, long after, Duration blockTimeout);

    default ConversationLiveEvent publishConversation(
            String conversationId,
            String runId,
            AnswerLiveEvent event
    ) {
        return null;
    }

    default List<ConversationLiveEvent> readConversationAfter(
            String conversationId,
            long after,
            Duration blockTimeout
    ) {
        return List.of();
    }

    void signalCancellation(String runId);

    boolean isCancellationRequested(String runId);
}
