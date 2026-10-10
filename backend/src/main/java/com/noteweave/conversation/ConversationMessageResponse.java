package com.noteweave.conversation;

import java.time.Instant;
import java.util.List;

public record ConversationMessageResponse(
        String messageId,
        int messageSeq,
        String role,
        String requestedTurnMode,
        String content,
        String replyToMessageId,
        String contextStatus,
        String contentHash,
        String answerStatus,
        String answerError,
        Instant createdAt,
        // 助手消息对应的回答运行，前端据此读取证据清单；用户消息与引用已撤销的消息为空。
        String answerRunId,
        // 引用文本，格式与回答流中的 citation.upsert 一致：标题 | 摘录。
        List<String> citations
) {
    public ConversationMessageResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
