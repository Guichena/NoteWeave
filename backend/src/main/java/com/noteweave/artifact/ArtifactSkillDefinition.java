package com.noteweave.artifact;

import java.util.Map;

public record ArtifactSkillDefinition(
        String skillKey,
        String displayName,
        Map<String, Object> inputSchema
) {
}
