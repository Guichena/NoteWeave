package com.noteweave.answer.strategy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record AnswerContext(
        String workspaceId,
        String conversationId,
        String queryMessageId,
        String query,
        Set<String> sourceScope,
        Map<String, String> attributes,
        Instant requestedAt
) {
    public AnswerContext {
        sourceScope = sourceScope == null ? Set.of() : Set.copyOf(sourceScope);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
    }
}
