package com.noteweave.team.rag.retriever;

import java.util.Map;
import lombok.Builder;

@Builder(toBuilder = true)
public record RetrievalHit(
        String retrieverName,
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        Long spaceId,
        Integer chunkIndex,
        String documentTitle,
        String content,
        Double score,
        Integer rank,
        Map<String, Object> metadata
) {
}
