package com.noteweave.knowledge;

import java.time.Instant;

public record KnowledgeItemResponse(
        String itemId,
        String itemType,
        String pageKind,
        String title,
        String status,
        String latestVersionId,
        int latestVersionNo,
        String summary,
        Instant updatedAt,
        int outgoingCount,
        int backlinkCount,
        int citationCount,
        int unresolvedCount,
        /** Wiki 页面内容来源状态（WIKI-03）：AUTO / MANUAL / NEEDS_REVIEW；Note 为 null。 */
        String wikiPageState
) {
}
