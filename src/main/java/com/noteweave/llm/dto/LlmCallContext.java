package com.noteweave.llm.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record LlmCallContext(
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
        List<LlmMessage> messages
) {
}
