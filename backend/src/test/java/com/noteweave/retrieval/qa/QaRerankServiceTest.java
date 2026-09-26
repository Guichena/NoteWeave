package com.noteweave.retrieval.qa;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.retrieval.provider.RerankClient;
import com.noteweave.retrieval.qa.QaRrfFusionService.FusedHit;
import java.util.List;
import org.junit.jupiter.api.Test;

class QaRerankServiceTest {

    @Test
    void usesProviderOrderAndScoresForFinalRanking() {
        QaRerankService service = new QaRerankService(new RerankClient() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public RerankResult rerank(String query, List<String> documents, int topN) {
                return new RerankResult(List.of(
                        new Hit(1, 0.95, 1),
                        new Hit(0, 0.71, 2)), "rerank-v1");
            }
        });

        QaRerankService.RerankOutcome outcome = service.rerank(
                "query", List.of(hit("a", 0.03), hit("b", 0.02)), 2);

        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.model()).isEqualTo("rerank-v1");
        assertThat(outcome.hits()).extracting(ranked -> ranked.hit().chunkId())
                .containsExactly("b", "a");
        assertThat(outcome.hits()).extracting(QaRerankService.RankedHit::rerankScore)
                .containsExactly(0.95, 0.71);
    }

    @Test
    void recordsExplicitDegradationWhenRerankIsUnavailable() {
        QaRerankService service = new QaRerankService(new RerankClient() {
            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public RerankResult rerank(String query, List<String> documents, int topN) {
                throw new AssertionError("disabled provider must not be called");
            }
        });

        QaRerankService.RerankOutcome outcome = service.rerank(
                "query", List.of(hit("a", 0.03), hit("b", 0.02)), 2);

        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.degradationReasons()).containsExactly("QA_RERANK_UNAVAILABLE");
        assertThat(outcome.hits()).extracting(ranked -> ranked.hit().chunkId())
                .containsExactly("a", "b");
    }

    @Test
    void keepsUnreturnedCandidatesBehindProviderRankedHits() {
        QaRerankService service = new QaRerankService(new RerankClient() {
            @Override public boolean isEnabled() { return true; }
            @Override public RerankResult rerank(String query, List<String> documents, int topN) {
                return new RerankResult(List.of(new Hit(1, 0.95, 1)), "rerank-v1");
            }
        });

        var outcome = service.rerank("query", List.of(hit("a", 0.03), hit("b", 0.02)), 2);

        assertThat(outcome.hits()).extracting(ranked -> ranked.hit().chunkId())
                .containsExactly("b", "a");
        assertThat(outcome.hits().get(1).fallbackScore()).isTrue();
    }

    @Test
    void emptyProviderResultShouldBeExplicitlyDegraded() {
        QaRerankService service = new QaRerankService(new RerankClient() {
            @Override public boolean isEnabled() { return true; }
            @Override public RerankResult rerank(String query, List<String> documents, int topN) {
                return new RerankResult(List.of(), "rerank-v1");
            }
        });

        var outcome = service.rerank("query", List.of(hit("a", 0.03), hit("b", 0.02)), 2);

        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.degradationReasons()).containsExactly("QA_RERANK_UNAVAILABLE");
        assertThat(outcome.hits()).extracting(ranked -> ranked.hit().chunkId()).containsExactly("a", "b");
    }

    @Test
    void invalidProviderIndexShouldBeExplicitlyDegraded() {
        QaRerankService service = new QaRerankService(new RerankClient() {
            @Override public boolean isEnabled() { return true; }
            @Override public RerankResult rerank(String query, List<String> documents, int topN) {
                return new RerankResult(List.of(new Hit(99, 0.8, 1)), "rerank-v1");
            }
        });

        var outcome = service.rerank("query", List.of(hit("a", 0.03), hit("b", 0.02)), 2);

        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.hits()).extracting(ranked -> ranked.hit().chunkId()).containsExactly("a", "b");
    }

    @Test
    void removesMarkdownAndUrlNoiseBeforeCallingProvider() {
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        QaRerankService service = new QaRerankService(new RerankClient() {
            @Override public boolean isEnabled() { return true; }
            @Override public RerankResult rerank(String query, List<String> documents, int topN) {
                captured.set(documents.get(0));
                return new RerankResult(List.of(new Hit(0, 0.8, 1)), "rerank-v1");
            }
        });
        FusedHit noisy = new FusedHit(
                "a", "source-a", "snapshot-1", 0, "Title", "Heading", "PDF",
                "# Header\n[useful](https://example.test) ![image](https://image.test/a.png)\n```java\nnoise();\n```",
                1, 1, 0.9, 9.0, 0.03, List.of("vector", "keyword"));

        service.rerank("query", List.of(noisy), 1);

        assertThat(captured.get()).contains("Header", "useful")
                .doesNotContain("https://", "noise();", "![image]");
    }

    private FusedHit hit(String id, double score) {
        return new FusedHit(
                id, "source-" + id, "snapshot-1", 0, "Title", "Heading", "PDF", "Content",
                1, 1, 0.9, 9.0, score, List.of("vector", "keyword"));
    }
}
