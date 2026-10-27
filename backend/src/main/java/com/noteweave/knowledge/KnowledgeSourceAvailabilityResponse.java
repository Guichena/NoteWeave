package com.noteweave.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 知识详情里引用来源的可用性说明；附加字段，旧前端可忽略。
 *
 * <p>status 与 reasonCode 来自 C1 资格判定；historyReadable/evidenceEligible/republishAllowed
 * 对应状态矩阵，前端据此决定是否渲染原文地址链接。</p>
 */
public record KnowledgeSourceAvailabilityResponse(
        @JsonProperty("source_id") String sourceId,
        String status,
        @JsonProperty("reason_code") String reasonCode,
        @JsonProperty("history_readable") boolean historyReadable,
        @JsonProperty("evidence_eligible") boolean evidenceEligible,
        @JsonProperty("republish_allowed") boolean republishAllowed,
        @JsonProperty("dependency_summary") List<String> dependencySummary
) {
    public KnowledgeSourceAvailabilityResponse {
        dependencySummary = dependencySummary == null ? List.of() : List.copyOf(dependencySummary);
    }
}
