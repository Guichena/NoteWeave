package com.noteweave.team.document.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.error.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkServiceTest {

    @Test
    void splitShouldCreateStableOverlappedChunks() {
        ChunkService chunkService = new ChunkService(40, 10);
        String text = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu";

        List<ChunkCandidate> chunks = chunkService.split(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).chunkIndex()).isZero();
        assertThat(chunks.get(1).sourceStart()).isLessThan(chunks.get(0).sourceEnd());
        assertThat(chunks.get(0).contentHash()).hasSize(64);
        assertThat(chunkService.split(text).get(0).contentHash()).isEqualTo(chunks.get(0).contentHash());
    }

    @Test
    void splitShouldNormalizeWhitespaceAndRejectEmptyText() {
        ChunkService chunkService = new ChunkService(80, 10);

        List<ChunkCandidate> chunks = chunkService.split("alpha\t\t beta\r\n\r\n gamma");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("alpha beta gamma");
        assertThatThrownBy(() -> chunkService.split(" \n\t "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void splitMarkdownShouldKeepSectionTitleMetadata() {
        ChunkService chunkService = new ChunkService(120, 20);

        List<ChunkCandidate> chunks = chunkService.split("# Deploy\n\nUse blue green rollout.");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sectionTitle()).isEqualTo("Deploy");
        assertThat(chunks.get(0).sourceStart()).isZero();
        assertThat(chunks.get(0).sourceEnd()).isGreaterThan(chunks.get(0).sourceStart());
    }
}
