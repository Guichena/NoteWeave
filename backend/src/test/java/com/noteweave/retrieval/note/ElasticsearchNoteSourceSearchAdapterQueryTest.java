package com.noteweave.retrieval.note;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.noteweave.retrieval.index.RetrievalIndexNames;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import org.junit.jupiter.api.Test;

class ElasticsearchNoteSourceSearchAdapterQueryTest {

    private final ElasticsearchNoteSourceSearchAdapter adapter =
            new ElasticsearchNoteSourceSearchAdapter(null);

    @Test
    void noteSourceAliasShouldBeSeparateFromQaChunkAlias() {
        assertThat(RetrievalIndexNames.alias(ProjectionType.NOTE_SOURCE, "Workspace 1"))
                .isEqualTo("noteweave_note_source_workspace_1");
        assertThat(RetrievalIndexNames.alias(ProjectionType.QA_CHUNK, "Workspace 1"))
                .isEqualTo("noteweave_qa_chunk_workspace_1");
    }

    @Test
    void vectorScopeFiltersByTheCurrentEmbeddingVersion() {
        Query query = adapter.vectorScope("workspace-1", "embedding-batch-v5:1024:abc");

        assertThat(query.isBool()).isTrue();
        assertThat(query.bool().filter()).hasSize(2);
        Query versionFilter = query.bool().filter().get(1);
        assertThat(versionFilter.isTerm()).isTrue();
        assertThat(versionFilter.term().field()).isEqualTo("embedding_version");
        assertThat(versionFilter.term().value().stringValue()).isEqualTo("embedding-batch-v5:1024:abc");
    }

    @Test
    void vectorScopeWithoutAVersionKeepsThePlainScope() {
        Query query = adapter.vectorScope("workspace-1", "");

        assertThat(query.bool().filter()).hasSize(1);
        assertThat(query.bool().filter().get(0).bool().filter()).hasSize(2);
    }
}
