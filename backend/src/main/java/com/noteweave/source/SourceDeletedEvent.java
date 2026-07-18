package com.noteweave.source;

public record SourceDeletedEvent(
        String workspaceId,
        String sourceId,
        String objectKey
) {
}
