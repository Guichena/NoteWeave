package com.noteweave.conversation;

public record ConversationSegmentSummaryBuildResponse(
        String segmentId,
        String summaryRevisionId,
        int coveredStartSeq,
        int coveredEndSeq,
        String revisionStatus,
        String taskId,
        String taskType,
        String taskStatus
) {
}
