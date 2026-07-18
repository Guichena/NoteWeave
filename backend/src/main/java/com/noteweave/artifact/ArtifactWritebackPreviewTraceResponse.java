package com.noteweave.artifact;

import java.util.List;

public record ArtifactWritebackPreviewTraceResponse(
        String status,
        String requestedMode,
        String allowedTarget,
        String executionMode,
        List<String> requiredCapabilities,
        String versionId,
        String requestId,
        String targetLocatorPreview,
        List<String> notes
) {
}
