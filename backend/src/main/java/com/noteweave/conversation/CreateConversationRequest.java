package com.noteweave.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateConversationRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 64) String conversationType
) {
}
