package com.noteweave.retrieval.note;

import java.util.List;

/** Empty note source search adapter used when Elasticsearch is disabled. */
public class NoOpNoteSourceSearchAdapter implements NoteSourceSearchPort {

    @Override
    public List<NoteSourceHit> semanticRetrieve(String workspaceId, List<Float> queryEmbedding, int topK) {
        return List.of();
    }

    @Override
    public List<NoteSourceHit> metadataRetrieve(String workspaceId, String query, int topK) {
        return List.of();
    }
}
