package com.noteweave.search.service;

public record SearchChunkHit(
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        Long spaceId,
        Integer indexVersion,
        Integer chunkIndex,
        Double score
) {
}
