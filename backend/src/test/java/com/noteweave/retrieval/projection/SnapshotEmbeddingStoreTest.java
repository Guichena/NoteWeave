package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.retrieval.projection.SourceRetrievalProjectionService.SnapshotEmbeddings;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotEmbeddingStoreTest {

    @Test
    void encodedEmbeddingsRoundTripExactly() throws Exception {
        SnapshotEmbeddings embeddings = new SnapshotEmbeddings("bge-m3:3", "bge-m3", 3,
                List.of("chunk-1", "chunk-2"), List.of("sha256:a", "sha256:b"),
                List.of(List.of(0.1f, -0.2f, 0.3f), List.of(1.5f, 0f, -7.25f)),
                "sha256:note", List.of(0.5f, 0.25f, 0.125f));

        SnapshotEmbeddings decoded = SnapshotEmbeddingStore.decode(SnapshotEmbeddingStore.encode(embeddings));

        assertThat(decoded).isEqualTo(embeddings);
        assertThat(decoded.matches("bge-m3:3", List.of("chunk-1", "chunk-2"))).isTrue();
        // 片段变化或向量版本变化时不能复用
        assertThat(decoded.matches("bge-m3:3", List.of("chunk-1"))).isFalse();
        assertThat(decoded.matches("bge-m3:1024", List.of("chunk-1", "chunk-2"))).isFalse();
    }
}
