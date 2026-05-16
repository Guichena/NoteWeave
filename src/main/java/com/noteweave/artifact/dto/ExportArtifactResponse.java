package com.noteweave.artifact.dto;

import lombok.Builder;

@Builder
public record ExportArtifactResponse(
        Long artifactId,
        String format,
        String fileName,
        String objectKey,
        String content
) {
}
