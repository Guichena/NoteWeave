package com.noteweave.knowledge;

import java.time.Instant;
import java.util.List;

public record WikiIndexSourceResponse(
        String sourceId,
        String title,
        String status,
        String indexStatus,
        List<WikiTaskRelatedPageResponse> relatedPages,
        String recommendedAction,
        String focusItemId,
        String focusTitle,
        Instant updatedAt
) {
}
