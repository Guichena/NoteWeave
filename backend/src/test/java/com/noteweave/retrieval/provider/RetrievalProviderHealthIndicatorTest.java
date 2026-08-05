package com.noteweave.retrieval.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class RetrievalProviderHealthIndicatorTest {

    @Test
    void isReadyWhenPrimaryProvidersAreHealthyOrMysqlFallbackIsEnabled() {
        RetrievalProviderHealthIndicator healthy = new RetrievalProviderHealthIndicator(
                embedding(true), rerank(true));
        RetrievalProviderHealthIndicator missingRerank = new RetrievalProviderHealthIndicator(
                embedding(true), rerank(false));
        RetrievalProviderHealthIndicator fallbackOnly = new RetrievalProviderHealthIndicator(
                embedding(false), rerank(false), true);

        assertThat(healthy.health().getStatus()).isEqualTo(Status.UP);
        assertThat(missingRerank.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(missingRerank.health().getDetails())
                .containsEntry("embedding_configured", true)
                .containsEntry("rerank_configured", false);
        assertThat(fallbackOnly.health().getStatus()).isEqualTo(Status.UP);
        assertThat(fallbackOnly.health().getDetails())
                .containsEntry("primary_available", false)
                .containsEntry("mysql_fallback_enabled", true)
                .containsEntry("degraded", true);
    }

    @Test
    void cachesActiveProviderProbesAcrossRepeatedHealthRequests() {
        AtomicInteger embeddingCalls = new AtomicInteger();
        AtomicInteger rerankCalls = new AtomicInteger();
        EmbeddingClient embedding = embedding(true, embeddingCalls);
        RerankClient rerank = rerank(true, rerankCalls);
        RetrievalProviderHealthIndicator indicator = new RetrievalProviderHealthIndicator(embedding, rerank);

        indicator.health();
        indicator.health();

        assertThat(embeddingCalls).hasValue(1);
        assertThat(rerankCalls).hasValue(1);
    }

    private EmbeddingClient embedding(boolean enabled) {
        return embedding(enabled, new AtomicInteger());
    }

    private EmbeddingClient embedding(boolean enabled, AtomicInteger calls) {
        return new EmbeddingClient() {
            @Override
            public boolean isEnabled() {
                return enabled;
            }

            @Override
            public EmbeddingResult embedQuery(String text) {
                calls.incrementAndGet();
                return new EmbeddingResult(List.of(List.of(1.0f)), "embedding", 1, 0);
            }

            @Override
            public EmbeddingResult embedDocuments(List<String> texts) {
                return new EmbeddingResult(List.of(), "", 0, 0);
            }
        };
    }

    private RerankClient rerank(boolean enabled) {
        return rerank(enabled, new AtomicInteger());
    }

    private RerankClient rerank(boolean enabled, AtomicInteger calls) {
        return new RerankClient() {
            @Override
            public boolean isEnabled() {
                return enabled;
            }

            @Override
            public RerankResult rerank(String query, List<String> documents, int topN) {
                calls.incrementAndGet();
                return new RerankResult(List.of(new Hit(0, 1.0d, 1)), "rerank");
            }
        };
    }
}
