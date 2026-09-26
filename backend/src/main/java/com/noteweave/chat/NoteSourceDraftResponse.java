package com.noteweave.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NoteSourceDraftResponse(
        @JsonProperty("message_id") String messageId,
        String title,
        String content,
        @JsonProperty("rewrite_mode") String rewriteMode,
        @JsonProperty("fallback_reason") String fallbackReason,
        @JsonProperty("source_content") String sourceContent
) {
}
