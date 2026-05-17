package com.noteweave.llm.dto;

public record TokenUsage(
        int inputTokens,
        int outputTokens,
        int totalTokens
) {

    public static TokenUsage of(int inputTokens, int outputTokens) {
        return new TokenUsage(inputTokens, outputTokens, Math.max(0, inputTokens) + Math.max(0, outputTokens));
    }
}
