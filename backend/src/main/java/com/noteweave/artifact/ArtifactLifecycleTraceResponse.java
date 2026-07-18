package com.noteweave.artifact;

import java.util.List;
import java.util.Map;

public record ArtifactLifecycleTraceResponse(
        String status,
        String currentPhase,
        List<ArtifactLifecycleStepTraceResponse> steps,
        List<String> notes,
        Map<String, Object> resumeScope
) {
}
