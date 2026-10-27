package com.noteweave.knowledge;

import java.time.Instant;
import java.util.List;

public record KnowledgeVersionDetailResponse(
        String versionId,
        String itemId,
        int versionNo,
        String content,
        String summary,
        String sourceMessageId,
        List<KnowledgeCitationResponse> citations,
        Instant createdAt,
        List<KnowledgeSourceAvailabilityResponse> sourceAvailability
) {
    /** 兼容旧构造（无可用性说明）：历史版本正文已按用途门控，可用性列表为空仅影响状态展示。 */
    public KnowledgeVersionDetailResponse(
            String versionId,
            String itemId,
            int versionNo,
            String content,
            String summary,
            String sourceMessageId,
            List<KnowledgeCitationResponse> citations,
            Instant createdAt
    ) {
        this(versionId, itemId, versionNo, content, summary, sourceMessageId, citations, createdAt, List.of());
    }
}
