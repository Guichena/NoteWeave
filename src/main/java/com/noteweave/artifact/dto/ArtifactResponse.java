package com.noteweave.artifact.dto;

import com.noteweave.artifact.model.ArtifactScopeType;
import com.noteweave.artifact.model.ArtifactStatus;
import com.noteweave.artifact.model.ArtifactType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record ArtifactResponse(
        Long id,
        Long userId,
        Long spaceId,
        Long researchProjectId,
        Long createdFromSessionId,
        Long createdFromMessageId,
        Long taskId,
        ArtifactType artifactType,
        String title,
        String content,
        ArtifactScopeType sourceScopeType,
        ArtifactStatus status,
        Integer latestVersionNo,
        List<ArtifactSourceDto> sources,
        List<ArtifactCitationDto> citations,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
