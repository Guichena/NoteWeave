package com.noteweave.knowledge;

public record WikiGraphEdge(
        String sourceItemId,
        String sourceTitle,
        String targetItemId,
        String targetTitle,
        String relationType,
        String relationStatus,
        int mentionCount
) {
}
