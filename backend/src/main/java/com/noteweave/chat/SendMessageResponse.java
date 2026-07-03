package com.noteweave.chat;

public record SendMessageResponse(
        String messageId,
        String assistantMessageId,
        String assistantRequestId,
        String streamUrl
) {
}
