package com.noteweave.knowledge;

import java.time.Instant;
import java.util.List;

/**
 * 知识详情响应。
 *
 * <p>sourceAvailability 是附加字段：逐项说明引用来源的资格状态与原因，
 * 旧调用方不读取该字段不受影响。</p>
 */
public record KnowledgeItemDetailResponse(
        String itemId,
        String itemType,
        String pageKind,
        String title,
        String status,
        String latestVersionId,
        int latestVersionNo,
        String content,
        String summary,
        String sourceMessageId,
        List<KnowledgeCitationResponse> citations,
        List<WikiLinkResponse> outgoingLinks,
        List<WikiLinkResponse> backlinks,
        Instant versionCreatedAt,
        Instant updatedAt,
        List<KnowledgeSourceAvailabilityResponse> sourceAvailability,
        /** Wiki 页面内容来源状态：AUTO / MANUAL / NEEDS_REVIEW；Note 为 null。原来只有列表接口返回。 */
        String wikiPageState
) {
    public KnowledgeItemDetailResponse(
            String itemId, String itemType, String pageKind, String title, String status,
            String latestVersionId, int latestVersionNo, String content, String summary,
            String sourceMessageId, List<KnowledgeCitationResponse> citations,
            List<WikiLinkResponse> outgoingLinks, List<WikiLinkResponse> backlinks,
            Instant versionCreatedAt, Instant updatedAt,
            List<KnowledgeSourceAvailabilityResponse> sourceAvailability
    ) {
        this(itemId, itemType, pageKind, title, status, latestVersionId, latestVersionNo,
                content, summary, sourceMessageId, citations, outgoingLinks, backlinks,
                versionCreatedAt, updatedAt, sourceAvailability, null);
    }

    /** 兼容旧调用方：不提供来源可用性说明。 */
    public KnowledgeItemDetailResponse(
            String itemId,
            String itemType,
            String pageKind,
            String title,
            String status,
            String latestVersionId,
            int latestVersionNo,
            String content,
            String summary,
            String sourceMessageId,
            List<KnowledgeCitationResponse> citations,
            List<WikiLinkResponse> outgoingLinks,
            List<WikiLinkResponse> backlinks,
            Instant versionCreatedAt,
            Instant updatedAt
    ) {
        this(itemId, itemType, pageKind, title, status, latestVersionId, latestVersionNo,
                content, summary, sourceMessageId, citations, outgoingLinks, backlinks,
                versionCreatedAt, updatedAt, List.of(), null);
    }
}
