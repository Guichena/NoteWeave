package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import org.junit.jupiter.api.Test;

/** IDX-02：写入前校验目标索引的向量版本，别名还指向旧索引时拒绝写入。 */
class SourceRetrievalProjectionVersionGuardTest {

    private static final String VERSION = "embedding-batch-v5:1024:abc123";

    @Test
    void acceptsAnIndexBuiltWithTheCurrentVersion() {
        assertThatCode(() -> SourceRetrievalProjectionService.requireIndexVersion(
                "noteweave_qa_chunk_vqa-v1_eembedding-batch-v5_1024_abc123_workspace", VERSION))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnIndexThatStillCarriesThePreviousVersion() {
        assertThatThrownBy(() -> SourceRetrievalProjectionService.requireIndexVersion(
                "noteweave_qa_chunk_vqa-v1_eembedding-batch-v5_1024_old999_workspace", VERSION))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    org.assertj.core.api.Assertions.assertThat(ex.code())
                            .isEqualTo("RETRIEVAL_INDEX_VERSION_MISMATCH");
                    org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                            .contains("请先重建索引并切换别名");
                });
    }

    @Test
    void acceptsLegacyIndexNamesWithoutAVersionSegment() {
        assertThatCode(() -> SourceRetrievalProjectionService.requireIndexVersion(
                "noteweave_qa_chunk_workspace", VERSION))
                .doesNotThrowAnyException();
    }
}
