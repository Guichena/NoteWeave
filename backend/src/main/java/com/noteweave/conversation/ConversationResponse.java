package com.noteweave.conversation;

import java.time.Instant;

public record ConversationResponse(String conversationId, String title, String conversationType, Instant createdAt) {
}
