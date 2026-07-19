package com.noteweave.retrieval.note;

import java.util.List;

public interface NoteSourceSearchPort {
    List<NoteSourceHit> semanticRetrieve(String workspaceId, List<Float> queryEmbedding, int topK);

    List<NoteSourceHit> metadataRetrieve(String workspaceId, String query, int topK);

    record NoteSourceHit(
            String sourceId,
            String sourceSnapshotId,
            String title,
            String sourceType,
            String summary,
            List<String> tags,
            String metadataText,
            double score
    ) {
        public NoteSourceHit {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }
}
