package com.noteweave.llm.dto;

import lombok.Builder;

@Builder
public record LlmOptions(
        Double temperature,
        Integer maxTokens
) {
}
