package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import com.noteweave.chat.RetrievalHydrator.PassageOwnership;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RerankClient;
import com.noteweave.retrieval.qa.QaHybridSearchPort;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaSearchHit;
import com.noteweave.retrieval.qa.QaRerankService;
import com.noteweave.retrieval.qa.QaRrfFusionService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QaHybridRetrieverProviderIntegrationTest {
    @Test
    void providerBackedQaPathRunsEmbeddingBothRecallsRrfRerankAndOwnership() {
        EmbeddingClient embedding = new StubEmbeddingClient();
        QaHybridSearchPort search = mock(QaHybridSearchPort.class);
        when(search.vectorRetrieve(any())).thenReturn(List.of(
                hit("semantic", 0.98), hit("shared", 0.80)));
        when(search.keywordRetrieve(any())).thenReturn(List.of(
                hit("shared", 12.0), hit("keyword", 9.0)));

        RerankClient rerank = new StubRerankClient();
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        when(hydrator.hydratePassageOwnership(eq("workspace"), any()))
                .thenReturn(Map.of(
                        "shared", new PassageOwnership("shared", "source-shared", "snapshot-shared", "", ""),
                        "semantic", new PassageOwnership("semantic", "source-semantic", "snapshot-semantic", "", ""),
                        "keyword", new PassageOwnership("keyword", "source-keyword", "snapshot-keyword", "", "")));
        when(hydrator.hydrateAdjacentPassages(eq("workspace"), any()))
                .thenReturn(Map.of("shared", List.of(new RetrievalHydrator.AdjacentPassage(
                        "shared", 1, "neighbor", 0, "intro", "neighbor context"))));

        QaHybridRetriever retriever = new QaHybridRetriever(
                embedding, search, new QaRrfFusionService(), new QaRerankService(rerank), hydrator,
                new QaQueryExpansionService());
        QaHybridRetriever.HybridResult result = retriever.retrieve("workspace", "resume checkpoint", Set.of());

        assertThat(result.degraded()).isFalse();
        assertThat(result.chunks()).extracting(QaPassageRetriever.RetrievedChunk::chunkId)
                .containsExactly("keyword", "shared", "semantic");
        assertThat(result.chunks()).allMatch(chunk -> chunk.matchReason().contains("weighted-rrf"));
        assertThat(result.chunks().stream().filter(chunk -> chunk.chunkId().equals("shared")).findFirst().orElseThrow().content())
                .contains("neighbor context", "checkpoint content shared");
        assertThat(result.measurements())
                .containsEntry("vector_candidate_count", 2L)
                .containsEntry("keyword_candidate_count", 2L)
                .containsEntry("rrf_candidate_count", 3L)
                .containsEntry("mysql_fallback_used", 0L);
        verify(search).vectorRetrieve(any());
        verify(search).keywordRetrieve(any());
    }

    @Test
    void rejectedSourceContentNeverReachesExternalReranker() {
        QaHybridSearchPort search = mock(QaHybridSearchPort.class);
        when(search.vectorRetrieve(any())).thenReturn(List.of(hit("allowed", 0.98), hit("revoked", 0.90)));
        when(search.keywordRetrieve(any())).thenReturn(List.of());
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        when(hydrator.hydratePassageOwnership(eq("workspace"), any())).thenReturn(Map.of(
                "allowed", new PassageOwnership("allowed", "source-allowed", "snapshot-allowed", "", "")));
        when(hydrator.hydrateAdjacentPassages(eq("workspace"), any())).thenReturn(Map.of());
        RerankClient rerank = mock(RerankClient.class);
        when(rerank.isEnabled()).thenReturn(true);
        when(rerank.rerank(any(), any(), anyInt())).thenReturn(new RerankClient.RerankResult(
                List.of(new RerankClient.Hit(0, 0.9, 1)), "stub-rerank"));

        QaHybridRetriever retriever = new QaHybridRetriever(
                new StubEmbeddingClient(), search, new QaRrfFusionService(),
                new QaRerankService(rerank), hydrator, new QaQueryExpansionService());
        QaHybridRetriever.HybridResult result = retriever.retrieve("workspace", "question", Set.of());

        ArgumentCaptor<List<String>> documents = ArgumentCaptor.forClass(List.class);
        verify(rerank).rerank(eq("question"), documents.capture(), eq(1));
        assertThat(documents.getValue()).hasSize(1).allMatch(text -> text.contains("allowed"))
                .allMatch(text -> !text.contains("revoked"));
        assertThat(result.chunks()).extracting(QaPassageRetriever.RetrievedChunk::chunkId)
                .containsExactly("allowed");
    }

    private QaSearchHit hit(String id, double score) {
        return new QaSearchHit(id, "source-" + id, "snapshot-" + id, 0,
                id, "heading", "MARKDOWN", "checkpoint content " + id, score);
    }

    private static final class StubEmbeddingClient implements EmbeddingClient {
        @Override public boolean isEnabled() { return true; }
        @Override public EmbeddingResult embedQuery(String text) {
            return new EmbeddingResult(List.of(List.of(1f, 0f, 0f)), "stub-embedding", 3, 2);
        }
        @Override public EmbeddingResult embedDocuments(List<String> texts) {
            return new EmbeddingResult(texts.stream().map(ignored -> List.of(1f, 0f, 0f)).toList(),
                    "stub-embedding", 3, texts.size());
        }
    }

    private static final class StubRerankClient implements RerankClient {
        @Override public boolean isEnabled() { return true; }
        @Override public RerankResult rerank(String query, List<String> documents, int topN) {
            return new RerankResult(List.of(
                    new Hit(2, 0.99, 1),
                    new Hit(0, 0.90, 2),
                    new Hit(1, 0.80, 3)), "stub-rerank");
        }
    }
}
