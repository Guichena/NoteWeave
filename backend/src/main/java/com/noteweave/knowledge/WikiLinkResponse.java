package com.noteweave.knowledge;

public record WikiLinkResponse(
        String sourceItemId,
        String targetItemId,
        String targetTitle,
        String relationType,
        String relationStatus,
        int mentionCount
) {
}
