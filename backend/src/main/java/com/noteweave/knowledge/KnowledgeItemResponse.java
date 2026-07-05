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
        int unresolvedCount
) {
}
