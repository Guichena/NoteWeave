package com.noteweave.retrieval.index;

import java.util.List;

public interface RetrievalProjectionWriter {
    void writeQaChunk(String targetIndex, QaChunkDocument document);

    void writeNoteSource(String targetIndex, NoteSourceDocument document);

    void markSnapshotNotCurrent(String targetIndex, String sourceSnapshotId);

    record QaChunkDocument(
            String workspaceId,
            String sourceId,
            String sourceSnapshotId,
            String chunkId,
            int chunkNo,
            String heading,
            String title,
            String sourceType,
            String content,
            String embeddingTextHash,
            String embeddingModel,
            int embeddingDimensions,
            String embeddingVersion,
            String projectionVersion,
            boolean currentSnapshot,
            List<Float> contentEmbedding
    ) {
        public QaChunkDocument {
            contentEmbedding = contentEmbedding == null ? List.of() : List.copyOf(contentEmbedding);
        }
    }

    record NoteSourceDocument(
            String workspaceId,
            String sourceId,
            String sourceSnapshotId,
            String title,
            String sourceType,
            String summary,
            List<String> tags,
            String metadataText,
            List<String> sectionDescriptions,
            List<String> catalogIds,
            int chunkCount,
            int windowCount,
            String embeddingTextHash,
            String embeddingModel,
            int embeddingDimensions,
            String embeddingVersion,
            String projectionVersion,
            boolean currentSnapshot,
            List<Float> sourceEmbedding
    ) {
        public NoteSourceDocument {
            tags = tags == null ? List.of() : List.copyOf(tags);
            sectionDescriptions = sectionDescriptions == null ? List.of() : List.copyOf(sectionDescriptions);
            catalogIds = catalogIds == null ? List.of() : List.copyOf(catalogIds);
            sourceEmbedding = sourceEmbedding == null ? List.of() : List.copyOf(sourceEmbedding);
        }
    }
}
