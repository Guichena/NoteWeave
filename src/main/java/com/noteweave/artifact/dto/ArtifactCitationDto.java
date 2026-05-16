package com.noteweave.artifact.dto;

import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record ArtifactCitationDto(
        Long id,
        String sourceType,
        Long sourceId,
        Long chunkId,
        String title,
        String quoteText,
        String locationInfo,
        Integer pageNo,
        Integer startOffset,
        Integer endOffset,
        String quoteHash,
        String snapshotObjectKey,
        String sourceVersion,
        LocalDateTime createdAt
) {
}
