package com.noteweave.knowledge;

public record WikiGraphMetaResponse(
        String mode,
        String centerItemId,
        int depth,
        int totalNodes,
        int returnedNodes,
        boolean truncated
) {
}
