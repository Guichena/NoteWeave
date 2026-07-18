package com.noteweave.source;

public record GeneratedSourceResult(
        String sourceId,
        String status,
        String parseStatus,
        String indexStatus,
        String generatedBy,
        String generatedRefId
) {
}
