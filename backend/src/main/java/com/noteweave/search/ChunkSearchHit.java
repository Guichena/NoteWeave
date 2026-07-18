package com.noteweave.search;

public record ChunkSearchHit(
        String chunkId,
        String sourceId,
        String sourceSnapshotId,
        String chunkNo,
        String title,
        String sourceType,
        String content,
        double score
) {
}
