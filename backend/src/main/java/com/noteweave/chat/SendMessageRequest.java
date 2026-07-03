package com.noteweave.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank @Size(max = 8000) String content,
        @NotBlank @Pattern(regexp = "QA|NOTE|WIKI") String answerMode,
        @NotBlank @Size(max = 120) String clientRequestId
) {
}
