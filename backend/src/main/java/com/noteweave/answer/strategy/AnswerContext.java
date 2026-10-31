package com.noteweave.answer.strategy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 一次回答的检索上下文。reportScope 是本轮允许取证的研究运行编号（REPORT 通道），
 * 与 sourceScope 一样属于声明范围：检索计划和证据都必须落在其中。
 */
public record AnswerContext(
        String workspaceId,
        String conversationId,
        String queryMessageId,
        String query,
        Set<String> sourceScope,
        Map<String, String> attributes,
        Instant requestedAt,
        Set<String> reportScope
) {
    public AnswerContext {
        sourceScope = sourceScope == null ? Set.of() : Set.copyOf(sourceScope);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
        reportScope = reportScope == null ? Set.of() : Set.copyOf(reportScope);
    }

    public AnswerContext(
            String workspaceId,
            String conversationId,
            String queryMessageId,
            String query,
            Set<String> sourceScope,
            Map<String, String> attributes,
            Instant requestedAt
    ) {
        this(workspaceId, conversationId, queryMessageId, query, sourceScope, attributes, requestedAt, Set.of());
    }
}
