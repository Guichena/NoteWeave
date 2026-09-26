package com.noteweave.retrieval.note;

import com.noteweave.retrieval.provider.RetrievalProviderException;
import java.util.List;

/** Failing note source search adapter used when Elasticsearch is disabled. */
public class NoOpNoteSourceSearchAdapter implements NoteSourceSearchPort {

    @Override
    public List<NoteSourceHit> semanticRetrieve(String workspaceId, List<Float> queryEmbedding, int topK) {
        throw disabled();
    }

    @Override
    public List<NoteSourceHit> metadataRetrieve(String workspaceId, String query, int topK) {
        throw disabled();
    }

    private RetrievalProviderException disabled() {
        return new RetrievalProviderException(
                "NOTE_SOURCE_SEARCH_PROVIDER_DISABLED",
                "Note source Elasticsearch search provider is disabled"
        );
    }
}
