package com.noteweave.memory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MemoryReviewDecisionRequest(
        @NotBlank String decision,
        @Size(max = 500) String reason
) {
    public MemoryReviewDecisionRequest {
        decision = decision == null ? null : MemorySignalService.normalizeToken(decision);
        reason = reason == null || reason.isBlank() ? null : reason.trim();
    }
}
