package com.noteweave.answer;

import java.time.Duration;
import java.util.List;

/** Short-lived cross-instance transport. MySQL remains the business truth. */
public interface AnswerRealtimeBridge {

    void publish(String runId, AnswerLiveEvent event);

    List<AnswerLiveEvent> readAfter(String runId, long after, Duration blockTimeout);

    ConversationLiveEvent publishConversation(
            String conversationId,
            String runId,
            AnswerLiveEvent event
    );

    List<ConversationLiveEvent> readConversationAfter(
            String conversationId,
            long after,
            Duration blockTimeout
    );

    /** 撤销传输副本；实现必须保留撤销标记以拒绝晚到写入。 */
    default void redact(String conversationId, String runId) {
        signalCancellation(runId);
    }

    void signalCancellation(String runId);

    /** 正文撤销与普通取消分离；撤销永久禁止重放或重新填充。 */
    default boolean isReplayRevoked(String runId) { return false; }

    boolean isCancellationRequested(String runId);
}
