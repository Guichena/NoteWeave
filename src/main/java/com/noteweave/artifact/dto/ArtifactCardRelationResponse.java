package com.noteweave.artifact.dto;

import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record ArtifactCardRelationResponse(
        Long id,
        Long artifactId,
        Long artifactVersionId,
        String cardType,
        Long cardId,
        String relationType,
        String cardTitle,
        LocalDateTime createdAt
) {
}
