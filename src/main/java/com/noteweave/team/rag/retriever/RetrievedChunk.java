package com.noteweave.team.rag.retriever;

public record RetrievedChunk(
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        Long spaceId,
        Integer indexVersion,
        Integer chunkIndex,
        String documentTitle,
        String content,
        Double score,
        Integer pageNo,
        Integer startOffset,
        Integer endOffset,
        String sourceVersion
) {
}
