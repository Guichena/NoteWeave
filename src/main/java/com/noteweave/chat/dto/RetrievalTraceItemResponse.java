package com.noteweave.chat.dto;

import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record RetrievalTraceItemResponse(
        Long id,
        String sourceType,
        Long sourceId,
        Long documentId,
        Long chunkId,
        Long wikiPageId,
        Double score,
        Integer rank,
        boolean selectedAsEvidence,
        String metadataJson,
        LocalDateTime createdAt
) {
}
