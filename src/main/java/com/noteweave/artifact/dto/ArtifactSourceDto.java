package com.noteweave.artifact.dto;

import lombok.Builder;

@Builder
public record ArtifactSourceDto(
        String sourceType,
        Long sourceId
) {
}
