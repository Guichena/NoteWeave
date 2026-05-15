package com.noteweave.team.document.chunk;

public record ChunkCandidate(
        int chunkIndex,
        String content,
        String contentHash,
        int tokenCount,
        int sourceStart,
        int sourceEnd,
        Integer pageNo,
        String sectionTitle
) {
}
