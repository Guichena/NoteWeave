package com.noteweave.memory;

import java.util.List;

public record MemoryObjectResponse(
        String memoryObjectId,
        String workspaceId,
        String memoryType,
        String memoryScope,
        String canonicalStatement,
        List<String> taskNeighborhoods,
        String status
) {
}
