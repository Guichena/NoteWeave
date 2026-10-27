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

    @Test
    void recognizesTheEmbeddingVersionCarriedByAnIndexName() {
        String current = RetrievalIndexNames.physical(
                ProjectionType.QA_CHUNK, "qa-v1", "embed-model:1024", "workspace");

        assertThat(RetrievalIndexNames.matchesEmbeddingVersion(current, "embed-model:1024")).isTrue();
        // 同一来源换了模型后，别名指向的旧索引不再承载当前版本
        assertThat(RetrievalIndexNames.matchesEmbeddingVersion(current, "embed-model:1536")).isFalse();
        // 前缀相同不算匹配：_e 段后必须紧跟版本分隔符
        assertThat(RetrievalIndexNames.matchesEmbeddingVersion(current, "embed-model:10")).isFalse();
        // 旧命名没有版本段，保持兼容
        assertThat(RetrievalIndexNames.matchesEmbeddingVersion(
                RetrievalIndexNames.physical(ProjectionType.QA_CHUNK, "1.0", "workspace"), "any"))
                .isTrue();
    }
}
