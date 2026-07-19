package com.noteweave.retrieval.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class RetrievalProviderHealthIndicatorTest {

    @Test
    void isReadyOnlyWhenBothProvidersAreConfigured() {
        RetrievalProviderHealthIndicator healthy = new RetrievalProviderHealthIndicator(
                embedding(true), rerank(true));
        RetrievalProviderHealthIndicator missingRerank = new RetrievalProviderHealthIndicator(
                embedding(true), rerank(false));

        assertThat(healthy.health().getStatus()).isEqualTo(Status.UP);
        assertThat(missingRerank.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(missingRerank.health().getDetails())
                .containsEntry("embedding_configured", true)
                .containsEntry("rerank_configured", false);
    }

    private EmbeddingClient embedding(boolean enabled) {
        return new EmbeddingClient() {
            @Override
            public boolean isEnabled() {
                return enabled;
            }

            @Override
            public EmbeddingResult embedQuery(String text) {
                return new EmbeddingResult(List.of(List.of(1.0f)), "embedding", 1, 0);
            }

            @Override
            public EmbeddingResult embedDocuments(List<String> texts) {
                return new EmbeddingResult(List.of(), "", 0, 0);
            }
        };
    }

    private RerankClient rerank(boolean enabled) {
        return new RerankClient() {
            @Override
            public boolean isEnabled() {
                return enabled;
            }

            @Override
            public RerankResult rerank(String query, List<String> documents, int topN) {
                return new RerankResult(List.of(new Hit(0, 1.0d, 1)), "rerank");
            }
        };
    }
}
