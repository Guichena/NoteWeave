package com.noteweave.retrieval.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.retrieval.index.RetrievalProjectionWriter.NoteSourceDocument;
import com.noteweave.retrieval.index.RetrievalProjectionWriter.QaChunkDocument;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ElasticsearchRetrievalProjectionWriterTest {
    private final ElasticsearchRetrievalProjectionWriter writer =
            new ElasticsearchRetrievalProjectionWriter(null);

    @Test
    void buildsCompleteQaChunkProjectionDocument() {
        QaChunkDocument document = new QaChunkDocument(
                "workspace-1", "source-1", "snapshot-1", "chunk-1", 2,
                "Heading", "Title", "PDF", "Evidence text", "hash-1",
                "embedding-v1", 3, "embedding-v1:3", "qa-index-v1", true,
                List.of(0.1f, 0.2f, 0.3f));

        Map<String, Object> indexed = writer.qaDocument(document);

        assertThat(indexed)
                .containsEntry("workspace_id", "workspace-1")
                .containsEntry("source_snapshot_id", "snapshot-1")
                .containsEntry("chunk_id", "chunk-1")
                .containsEntry("content", "Evidence text")
                .containsEntry("is_current_snapshot", true)
                .containsEntry("content_embedding", List.of(0.1f, 0.2f, 0.3f));
    }

    @Test
    void buildsStructuredNoteSourceProjectionWithoutRawFullText() {
        NoteSourceDocument document = new NoteSourceDocument(
                "workspace-1", "source-1", "snapshot-1", "Title", "PDF", "Summary",
                List.of("rag", "retrieval"), "author: example",
                List.of("Introduction", "Evaluation"), List.of("research"),
                12, 24, "hash-2", "embedding-v1", 2,
                "embedding-v1:2", "note-index-v1", true, List.of(0.4f, 0.5f));

        Map<String, Object> indexed = writer.noteDocument(document);

        assertThat(indexed)
                .containsEntry("summary", "Summary")
                .containsEntry("tags", List.of("rag", "retrieval"))
                .containsEntry("section_descriptions", List.of("Introduction", "Evaluation"))
                .containsEntry("source_embedding", List.of(0.4f, 0.5f))
                .doesNotContainKeys("content", "content_embedding");
    }

    @Test
    void rejectsProjectionWhenVectorShapeDoesNotMatchVersionContract() {
        QaChunkDocument document = new QaChunkDocument(
                "workspace-1", "source-1", "snapshot-1", "chunk-1", 0,
                "", "Title", "PDF", "Content", "hash", "embedding-v1", 3,
                "embedding-v1:3", "qa-index-v1", true, List.of(0.1f, 0.2f));

        assertThatThrownBy(() -> writer.writeQaChunk("index", document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions");
    }
}
