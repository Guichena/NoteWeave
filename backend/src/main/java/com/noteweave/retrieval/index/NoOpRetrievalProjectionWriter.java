package com.noteweave.retrieval.index;

import com.noteweave.retrieval.provider.RetrievalProviderException;

/** Failing projection writer used when Elasticsearch is disabled. */
public class NoOpRetrievalProjectionWriter implements RetrievalProjectionWriter {

    @Override
    public void writeQaChunk(String targetIndex, QaChunkDocument document) {
        throw disabled();
    }

    @Override
    public void writeNoteSource(String targetIndex, NoteSourceDocument document) {
        throw disabled();
    }

    @Override
    public void markSnapshotNotCurrent(String targetIndex, String sourceSnapshotId) {
        throw disabled();
    }

    @Override
    public void markSnapshotCurrent(String targetIndex, String sourceSnapshotId) {
        throw disabled();
    }

    @Override
    public void deleteSource(String targetIndex, String sourceId) {
        throw disabled();
    }

    private RetrievalProviderException disabled() {
        return new RetrievalProviderException(
                "RETRIEVAL_PROJECTION_PROVIDER_DISABLED",
                "Elasticsearch retrieval projection provider is disabled"
        );
    }
}
