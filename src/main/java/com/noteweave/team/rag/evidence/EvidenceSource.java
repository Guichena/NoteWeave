package com.noteweave.team.rag.evidence;

public record EvidenceSource(
        Long chunkId,
        Integer chunkIndex,
        Integer pageNo,
        Integer startOffset,
        Integer endOffset,
        String quoteText,
        String sourceVersion
) {
}
