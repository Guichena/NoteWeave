package com.noteweave.llm.dto;

import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record LlmCallLogResponse(
        Long id,
        Long userId,
        Long spaceId,
        Long sessionId,
        Long messageId,
        Long taskId,
        Long artifactId,
        String scene,
        String provider,
        String model,
        Long promptVersionId,
        String promptHash,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        Long latencyMs,
        boolean success,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt
) {
}
