package com.noteweave.source;

import java.util.List;

public record SourceDeletedEvent(
        String workspaceId,
        String sourceId,
        List<String> objectKeys
) {
    public SourceDeletedEvent {
        objectKeys = objectKeys == null ? List.of() : objectKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
    }

    public SourceDeletedEvent(String workspaceId, String sourceId, String objectKey) {
        this(workspaceId, sourceId,
                objectKey == null || objectKey.isBlank() ? List.of() : List.of(objectKey));
    }
}
