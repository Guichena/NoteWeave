package com.noteweave.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PromoteSegmentSummaryRequest(
        @NotBlank String summaryText,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String contentHash,
        @Pattern(regexp = "LLM_INCREMENTAL|LLM_FULL|EXTRACTIVE") String summaryMethod
) {
    public PromoteSegmentSummaryRequest {
        if (summaryMethod == null || summaryMethod.isBlank()) {
            summaryMethod = ConversationSummaryGenerator.METHOD_EXTRACTIVE;
        }
    }

    public PromoteSegmentSummaryRequest(String summaryText, String contentHash) {
        this(summaryText, contentHash, ConversationSummaryGenerator.METHOD_EXTRACTIVE);
    }
}
