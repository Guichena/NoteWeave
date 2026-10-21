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
    void partialRerankKeepsUnscoredCandidatesBehindInOriginalOrder() {
        QaRerankService service = new QaRerankService(new RerankClient() {
            @Override public boolean isEnabled() { return true; }
            @Override public RerankResult rerank(String query, List<String> documents, int topN) {
                return new RerankResult(List.of(
                        new Hit(2, 0.80, 1),
                        new Hit(0, 0.20, 2)), "rerank-v1");
            }
        });

        var outcome = service.rerank("query", List.of(
                hit("a", 0.50), hit("b", 0.40), hit("c", 0.30), hit("d", 0.20), hit("e", 0.10)), 5);

        assertThat(outcome.hits()).extracting(ranked -> ranked.hit().chunkId())
                .containsExactly("c", "a", "b", "d", "e");
        assertThat(outcome.hits().subList(0, 2)).allMatch(ranked -> !ranked.fallbackScore());
        assertThat(outcome.hits().subList(2, 5)).allMatch(QaRerankService.RankedHit::fallbackScore);
        double lowestScored = Math.min(
                outcome.hits().get(0).rerankScore(), outcome.hits().get(1).rerankScore());
        assertThat(outcome.hits().subList(2, 5)).allSatisfy(ranked ->
                assertThat(ranked.rerankScore()).isLessThan(lowestScored));
        assertThat(outcome.hits().get(3).rerankScore()).isLessThan(outcome.hits().get(2).rerankScore());
        assertThat(outcome.hits().get(4).rerankScore()).isLessThan(outcome.hits().get(3).rerankScore());
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
    void removesMarkdownNoiseAndKeepsCodeBeforeCallingProvider() {
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

        assertThat(captured.get()).contains("Header", "useful", "noise();")
                .doesNotContain("https://", "![image]", "```");
    }

    @Test
    void keepsCodeIdentifiersAndBareUrls() {
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        QaRerankService service = new QaRerankService(new RerankClient() {
            @Override public boolean isEnabled() { return true; }
            @Override public RerankResult rerank(String query, List<String> documents, int topN) {
                captured.set(documents.get(0));
                return new RerankResult(List.of(new Hit(0, 0.8, 1)), "rerank-v1");
            }
        });
        FusedHit source = new FusedHit(
                "a", "source-a", "snapshot-1", 0, "Title", "Heading", "PDF",
                "Docs live at https://docs.example.com/api/v1.\n```java\nloadWorkspaceSnapshot(workspaceId);\n```",
                1, 1, 0.9, 9.0, 0.03, List.of("vector", "keyword"));

        service.rerank("query", List.of(source), 1);

        assertThat(captured.get())
                .contains("loadWorkspaceSnapshot", "workspaceId", "https://docs.example.com/api/v1")
                .doesNotContain("```", " java");
    }

    @Test
    void stripsPresignedSignatureQueryParamsFromBareUrls() {
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        QaRerankService service = new QaRerankService(new RerankClient() {
            @Override public boolean isEnabled() { return true; }
            @Override public RerankResult rerank(String query, List<String> documents, int topN) {
                captured.set(documents.get(0));
                return new RerankResult(List.of(new Hit(0, 0.8, 1)), "rerank-v1");
            }
        });
        FusedHit source = new FusedHit(
                "a", "source-a", "snapshot-1", 0, "Title", "Heading", "PDF",
                "MinIO https://minio.example.test/notes/chunk.pdf"
                        + "?response-content-type=application%2Fpdf"
                        + "&X-Amz-Algorithm=AWS4-HMAC-SHA256"
                        + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20260101%2Fus-east-1%2Fs3%2Faws4_request"
                        + "&X-Amz-Date=20260101T000000Z&X-Amz-Expires=3600"
                        + "&X-Amz-SignedHeaders=host"
                        + "&X-Amz-Security-Token=IQoJb3JpZ2luX2VjEL"
                        + "&X-Amz-Signature=8d2f3c1b9a0e "
                        + "Azure https://files.example.test/item?Signature=hmac-secret&Expires=123 "
                        + "CDN https://cdn.example.test/a.mp4?token=secret-session&sig=1a2b&name=lecture.mp4 "
                        + "Docs https://docs.example.com/api/v1.",
                1, 1, 0.9, 9.0, 0.03, List.of("vector", "keyword"));

        service.rerank("query", List.of(source), 1);

        assertThat(captured.get())
                .contains("https://minio.example.test/notes/chunk.pdf?response-content-type=application%2Fpdf")
                .contains("https://files.example.test/item?Expires=123")
                .contains("https://cdn.example.test/a.mp4?name=lecture.mp4")
                .contains("https://docs.example.com/api/v1")
                .doesNotContain(
                        "X-Amz-Signature",
                        "X-Amz-Credential",
                        "X-Amz-Security-Token",
                        "X-Amz-Algorithm",
                        "AKIAIOSFODNN7EXAMPLE",
                        "IQoJb3JpZ2luX2VjEL",
                        "8d2f3c1b9a0e",
                        "Signature=hmac-secret",
                        "token=secret-session",
                        "sig=1a2b");
    }

    private FusedHit hit(String id, double score) {
        return new FusedHit(
                id, "source-" + id, "snapshot-1", 0, "Title", "Heading", "PDF", "Content",
                1, 1, 0.9, 9.0, score, List.of("vector", "keyword"));
    }
}
