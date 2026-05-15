package com.noteweave.llm.dto;

import lombok.Builder;

@Builder
public record LlmResponse(
        String provider,
        String model,
        String content,
        int inputTokens,
        int outputTokens,
        long latencyMs
) {
}
