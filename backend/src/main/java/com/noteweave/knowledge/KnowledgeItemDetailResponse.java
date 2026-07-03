package com.noteweave.knowledge;

import java.time.Instant;
import java.util.List;

public record KnowledgeItemDetailResponse(
        String itemId,
        String itemType,
        String title,
        String status,
        String latestVersionId,
        int latestVersionNo,
        String content,
        String summary,
        List<KnowledgeCitationResponse> citations,
        List<WikiLinkResponse> outgoingLinks,
        List<WikiLinkResponse> backlinks,
        Instant updatedAt
) {
}
