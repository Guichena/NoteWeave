package com.noteweave.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record AnswerFeedbackRequest(
        @NotBlank @Size(max = 16) String rating,
        @Size(max = 500) String reason
) {
    public AnswerFeedbackRequest {
        rating = rating == null ? null : rating.trim().toUpperCase(Locale.ROOT);
        reason = reason == null || reason.isBlank() ? null : reason.trim();
    }
}
