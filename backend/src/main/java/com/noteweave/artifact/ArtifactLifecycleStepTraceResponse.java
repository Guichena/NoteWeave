package com.noteweave.artifact;

import java.util.Map;

public record ArtifactLifecycleStepTraceResponse(
        String phase,
        String status,
        int progressPercent,
        String message,
        Map<String, Object> metrics
) {
}
