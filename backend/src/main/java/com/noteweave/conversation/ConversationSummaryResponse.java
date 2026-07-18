package com.noteweave.conversation;

import java.time.Instant;

public record ConversationSummaryResponse(
        String conversationId,
        String title,
        String conversationType,
        String status,
        String activeHeadMessageId,
        Instant createdAt,
        Instant lastActiveAt
) {
}

