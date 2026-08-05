package com.noteweave.retrieval.index;

/** No-op projection writer used when Elasticsearch is disabled. */
public class NoOpRetrievalProjectionWriter implements RetrievalProjectionWriter {

    @Override
    public void writeQaChunk(String targetIndex, QaChunkDocument document) {
    }

    @Override
    public void writeNoteSource(String targetIndex, NoteSourceDocument document) {
    }

    @Override
    public void markSnapshotNotCurrent(String targetIndex, String sourceSnapshotId) {
    }

    @Override
    public void markSnapshotCurrent(String targetIndex, String sourceSnapshotId) {
    }

    @Override
    public void deleteSource(String targetIndex, String sourceId) {
    }
}
