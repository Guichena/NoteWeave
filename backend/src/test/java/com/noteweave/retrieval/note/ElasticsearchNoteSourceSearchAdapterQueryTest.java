package com.noteweave.retrieval.note;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.retrieval.index.RetrievalIndexNames;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import org.junit.jupiter.api.Test;

class ElasticsearchNoteSourceSearchAdapterQueryTest {
    @Test
    void noteSourceAliasShouldBeSeparateFromQaChunkAlias() {
        assertThat(RetrievalIndexNames.alias(ProjectionType.NOTE_SOURCE, "Workspace 1"))
                .isEqualTo("noteweave_note_source_workspace_1");
        assertThat(RetrievalIndexNames.alias(ProjectionType.QA_CHUNK, "Workspace 1"))
                .isEqualTo("noteweave_qa_chunk_workspace_1");
    }
}
