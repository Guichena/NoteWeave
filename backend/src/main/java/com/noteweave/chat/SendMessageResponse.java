package com.noteweave.chat;

public record SendMessageResponse(
        String messageId,
        String assistantRequestId,
        String streamUrl
) {
}
