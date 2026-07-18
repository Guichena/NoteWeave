package com.noteweave.artifact;

import java.util.List;
import java.util.Map;

public record ArtifactSkillSummaryResponse(
        String skillKey,
        String displayName,
        String description,
        String status,
        Map<String, Object> inputSchema,
        List<String> defaultInputHints
) {
}
