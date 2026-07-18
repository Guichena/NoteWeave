package com.noteweave.memory;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMemoryOutcomeRequest(
        @NotBlank String targetType,
        @NotBlank String targetId,
        @NotBlank String outcomeType,
        @DecimalMin("0.0") @DecimalMax("1.0") Double editMagnitude,
        @Size(max = 500) String feedbackNote
) {
    public CreateMemoryOutcomeRequest {
        targetType = normalize(targetType);
        targetId = targetId == null ? null : targetId.trim();
        outcomeType = normalize(outcomeType);
        feedbackNote = feedbackNote == null || feedbackNote.isBlank()
                ? null : feedbackNote.trim();
    }

    private static String normalize(String value) {
        return value == null ? null : MemorySignalService.normalizeToken(value);
    }
}
