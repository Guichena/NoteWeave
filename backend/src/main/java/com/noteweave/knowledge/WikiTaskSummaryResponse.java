package com.noteweave.knowledge;

import java.time.Instant;

public record WikiTaskSummaryResponse(
        String taskId,
        String taskType,
        String taskStatus,
        String progressPhase,
        String progressMessage,
        String targetType,
        String targetId,
        String targetTitle,
        java.util.List<WikiTaskRelatedPageResponse> relatedPages,
        Instant updatedAt
) {
}
