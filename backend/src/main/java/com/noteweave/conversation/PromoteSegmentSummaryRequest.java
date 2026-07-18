package com.noteweave.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PromoteSegmentSummaryRequest(
        @NotBlank String summaryText,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String contentHash
) {
}
