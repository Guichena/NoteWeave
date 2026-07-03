package com.noteweave.knowledge;

import java.time.Instant;

public record KnowledgeItemResponse(
        String itemId,
        String itemType,
        String title,
        String status,
        String latestVersionId,
        int latestVersionNo,
        String summary,
        Instant updatedAt
) {
}
