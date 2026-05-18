package com.noteweave.studio.dto;

import lombok.Builder;

@Builder
public record StudioSkillResponse(
        String id,
        String name,
        String artifactType,
        String sourceScopeType,
        String description,
        String topicHint
) {
}
