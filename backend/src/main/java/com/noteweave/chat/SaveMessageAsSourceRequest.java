package com.noteweave.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SaveMessageAsSourceRequest(
        @NotBlank @Size(max = 300) String title,
        @Size(max = 100_000) String content
) {
}
