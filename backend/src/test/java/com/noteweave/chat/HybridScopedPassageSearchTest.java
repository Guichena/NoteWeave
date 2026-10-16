package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.chat.QaPassageRetriever.RetrievedChunk;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import com.noteweave.retrieval.qa.ScopedPassageSearch;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HybridScopedPassageSearchTest {

    @Test
    void limitsPassagesAndUsesTheRerankScore() {
        QaHybridRetriever retriever = mock(QaHybridRetriever.class);
        when(retriever.retrieve("workspace", "独立问题", Set.of("source-a"))).thenReturn(new QaHybridRetriever.HybridResult(
                List.of(chunk("a", 0.2d), chunk("b", 0.8d)),
                false,
                List.of(),
                java.util.Map.of(),
                "embed",
                "rerank"));
        HybridScopedPassageSearch search = new HybridScopedPassageSearch(retriever);

        ScopedPassageSearch.Result result = search.search("workspace", Set.of("source-a"), "独立问题", 1);

        assertThat(result.degraded()).isFalse();
        assertThat(result.passages()).singleElement().satisfies(passage -> {
            assertThat(passage.chunkId()).isEqualTo("a");
            assertThat(passage.location()).isEqualTo("loc-a");
            assertThat(passage.score()).isEqualTo(0.2d);
        });
    }

    @Test
    void providerFailureIsDegradedInsteadOfThrown() {
        QaHybridRetriever retriever = mock(QaHybridRetriever.class);
        when(retriever.retrieve("workspace", "独立问题", Set.of())).thenThrow(
                new RetrievalProviderException("EMBEDDING_PROVIDER_DISABLED", "disabled"));
        HybridScopedPassageSearch search = new HybridScopedPassageSearch(retriever);

        ScopedPassageSearch.Result result = search.search("workspace", Set.of(), "独立问题", 5);

        assertThat(result.passages()).isEmpty();
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradationReasons()).containsExactly("EMBEDDING_PROVIDER_DISABLED");
    }

    @Test
    void blankQueryDoesNotCallRetrieval() {
        QaHybridRetriever retriever = mock(QaHybridRetriever.class);
        HybridScopedPassageSearch search = new HybridScopedPassageSearch(retriever);

        ScopedPassageSearch.Result result = search.search("workspace", Set.of("source-a"), "  ", 5);

        assertThat(result.passages()).isEmpty();
        assertThat(result.degraded()).isFalse();
        verify(retriever, never()).retrieve(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anySet());
    }

    private RetrievedChunk chunk(String id, double rerankScore) {
        return new RetrievedChunk(
                id, "source-" + id, "snapshot-" + id, 1, "title", "content",
                "loc-" + id, "MARKDOWN", "", "", 1, "reason",
                0.1d, 0.4d, rerankScore);
    }
}
