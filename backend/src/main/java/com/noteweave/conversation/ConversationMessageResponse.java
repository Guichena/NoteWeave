package com.noteweave.conversation;

import java.time.Instant;

public record ConversationMessageResponse(
        String messageId,
        int messageSeq,
        String role,
        String requestedTurnMode,
        String content,
        String replyToMessageId,
        String contextStatus,
        String contentHash,
        Instant createdAt
) {
}

