package com.noteweave.worker;

import java.util.Map;

public record WorkerSourceScopeItemResponse(
        String sourceId,
        String title,
        String summary,
        String sampleText,
        String sourceSnapshotId,
        String sourceWindowId,
        String generatedBy,
        String generatedRefId,
        String sourceType,
        String sourceUri,
        Map<String, Object> sourceMetadata
) {
}
