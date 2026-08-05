package com.noteweave.retrieval.index;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import org.junit.jupiter.api.Test;

class RetrievalIndexManagerMappingTest {
    private final RetrievalIndexManager manager = RetrievalIndexManager.withClient(null);

    @Test
    void qaMappingContainsIndexedCosineChunkVectorAndScopeFields() {
        TypeMapping mapping = manager.mapping(ProjectionType.QA_CHUNK, 1024);

        assertThat(mapping.properties()).containsKeys(
                "workspace_id", "source_id", "source_snapshot_id", "chunk_id",
                "content", "content_embedding", "is_current_snapshot");
        assertThat(mapping.properties().get("content_embedding").denseVector().dims()).isEqualTo(1024);
        assertThat(mapping.properties().get("content_embedding").denseVector().index()).isTrue();
        assertThat(mapping.properties().get("content_embedding").denseVector().similarity())
                .isEqualTo("cosine");
    }

    @Test
    void noteMappingUsesSourceLevelVectorAndStructuredMetadata() {
        TypeMapping mapping = manager.mapping(ProjectionType.NOTE_SOURCE, 768);

        assertThat(mapping.properties()).containsKeys(
                "workspace_id", "source_id", "source_snapshot_id", "summary", "tags",
                "metadata_text", "section_descriptions", "source_embedding");
        assertThat(mapping.properties()).doesNotContainKey("content_embedding");
        assertThat(mapping.properties().get("source_embedding").denseVector().dims()).isEqualTo(768);
    }
}
