package com.noteweave.chat.dto;

import lombok.Builder;

@Builder
public record RetrievalTraceItemCreateRequest(
        String sourceType,
        Long sourceId,
        Long documentId,
        Long chunkId,
        Long wikiPageId,
        Double score,
        Integer rank,
        boolean selectedAsEvidence,
        String metadataJson
) {
}
