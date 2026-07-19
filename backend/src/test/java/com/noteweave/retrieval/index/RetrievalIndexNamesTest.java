package com.noteweave.retrieval.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import org.junit.jupiter.api.Test;

class RetrievalIndexNamesTest {

    @Test
    void buildsStableQaAndNoteIndexFamilies() {
        assertThat(RetrievalIndexNames.physical(
                ProjectionType.QA_CHUNK, "1.0", "Workspace-ABC"))
                .isEqualTo("noteweave_qa_chunk_v1_0_workspace-abc");
        assertThat(RetrievalIndexNames.alias(
                ProjectionType.QA_CHUNK, "Workspace-ABC"))
                .isEqualTo("noteweave_qa_chunk_workspace-abc");
        assertThat(RetrievalIndexNames.physical(
                ProjectionType.NOTE_SOURCE, "note-source-v1", "Workspace-ABC"))
                .isEqualTo("noteweave_note_source_vnote-source-v1_workspace-abc");
    }

    @Test
    void embeddingVersionCreatesANewPhysicalGeneration() {
        assertThat(RetrievalIndexNames.physical(
                ProjectionType.QA_CHUNK, "qa-v1", "embed-model:1024", "workspace"))
                .isEqualTo("noteweave_qa_chunk_vqa-v1_eembed-model_1024_workspace");
        assertThat(RetrievalIndexNames.physical(
                ProjectionType.QA_CHUNK, "qa-v1", "embed-model:1536", "workspace"))
                .isNotEqualTo(RetrievalIndexNames.physical(
                        ProjectionType.QA_CHUNK, "qa-v1", "embed-model:1024", "workspace"));
    }
}
