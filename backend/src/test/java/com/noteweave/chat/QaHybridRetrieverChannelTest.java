package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.chat.RetrievalHydrator.PassageOwnership;
import com.noteweave.retrieval.RetrievalChannels;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RerankClient;
import com.noteweave.retrieval.qa.QaHybridSearchPort;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaKeywordQuery;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaSearchHit;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaVectorQuery;
import com.noteweave.retrieval.qa.QaRerankService;
import com.noteweave.retrieval.qa.QaRrfFusionService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QaHybridRetrieverChannelTest {

    @Test
    void llmChannelsUseSeparateQueriesAndAlternativesInsteadOfRuleExpansion() {
        Harness harness = harness();
        RetrievalChannels channels = new RetrievalChannels(
                "vector question",
                "keyword question",
                "vector question",
                List.of("alternative question"),
                List.of("MustTerm"),
                List.of("TitleTerm"),
                List.of("BodyTerm"),
                false);

        harness.retriever().retrieve("workspace", channels, Set.of());

        ArgumentCaptor<QaVectorQuery> vectors = ArgumentCaptor.forClass(QaVectorQuery.class);
        verify(harness.search(), atLeast(2)).vectorRetrieve(vectors.capture());
        assertThat(vectors.getAllValues()).extracting(QaVectorQuery::query)
                .contains("vector question", "alternative question");
        ArgumentCaptor<QaKeywordQuery> keywords = ArgumentCaptor.forClass(QaKeywordQuery.class);
        verify(harness.search(), atLeast(2)).keywordRetrieve(keywords.capture());
        assertThat(keywords.getAllValues()).anySatisfy(query -> {
            assertThat(query.query()).isEqualTo("keyword question");
            assertThat(query.mustTerms()).containsExactly("MustTerm");
            assertThat(query.titleTerms()).containsExactly("TitleTerm");
            assertThat(query.bodyTerms()).containsExactly("BodyTerm");
        });
        assertThat(keywords.getAllValues()).anySatisfy(query -> {
            assertThat(query.query()).isEqualTo("alternative question");
            assertThat(query.mustTerms()).isEmpty();
            assertThat(query.titleTerms()).isEmpty();
            assertThat(query.bodyTerms()).isEmpty();
        });
        verify(harness.expansion(), never()).expand(any());
        verify(harness.rerank()).rerank(eq("vector question"), any(), anyInt());
    }

    @Test
    void ruleChannelsExpandOnlyAfterLowRecallAndDoNotBoostTheExtraQuery() {
        Harness harness = harness();
        when(harness.expansion().expand("vector question")).thenReturn(List.of("rule expanded"));
        RetrievalChannels channels = new RetrievalChannels(
                "vector question",
                "keyword question",
                "vector question",
                List.of("ignored alternative"),
                List.of("MustTerm"),
                List.of(),
                List.of(),
                true);

        harness.retriever().retrieve("workspace", channels, Set.of());

        verify(harness.expansion()).expand("vector question");
        ArgumentCaptor<QaKeywordQuery> keywords = ArgumentCaptor.forClass(QaKeywordQuery.class);
        verify(harness.search(), atLeast(2)).keywordRetrieve(keywords.capture());
        assertThat(keywords.getAllValues()).anySatisfy(query -> {
            assertThat(query.query()).isEqualTo("keyword question");
            assertThat(query.mustTerms()).containsExactly("MustTerm");
        });
        assertThat(keywords.getAllValues()).anySatisfy(query -> {
            assertThat(query.query()).isEqualTo("rule expanded");
            assertThat(query.mustTerms()).isEmpty();
        });
        assertThat(keywords.getAllValues()).extracting(QaKeywordQuery::query)
                .doesNotContain("ignored alternative");
    }

    @Test
    void vectorFailureKeepsKeywordScoresAndNeverRequiresLocalFallback() {
        Harness harness = harness();
        when(harness.search().vectorRetrieve(any())).thenThrow(new IllegalStateException("private failure"));
        when(harness.search().keywordRetrieve(any())).thenReturn(List.of(hit("only")));
        var result = harness.retriever().retrieve("workspace", "query", Set.of());
        assertThat(result.chunks()).extracting(QaPassageRetriever.RetrievedChunk::chunkId).containsExactly("only");
        assertThat(result.degradationReasons()).containsExactly("QA_VECTOR_RECALL_UNAVAILABLE");
        assertThat(result.measurements()).containsEntry("vector_candidate_count", 0L)
                .containsEntry("keyword_candidate_count", 1L).containsEntry("mysql_fallback_used", 0L);
    }

    @Test
    void keywordFailureKeepsVectorResults() {
        Harness harness = harness();
        when(harness.search().keywordRetrieve(any())).thenThrow(new IllegalStateException("private failure"));
        var result = harness.retriever().retrieve("workspace", "query", Set.of());
        assertThat(result.chunks()).hasSize(1);
        assertThat(result.degradationReasons()).containsExactly("QA_KEYWORD_RECALL_UNAVAILABLE");
    }

    @Test
    void bothFailuresFailButSuccessfulEmptyChannelDoesNot() {
        Harness harness = harness();
        when(harness.search().vectorRetrieve(any())).thenThrow(new IllegalStateException("private failure"));
        var empty = harness.retriever().retrieve("workspace", "query", Set.of());
        assertThat(empty.chunks()).isEmpty();
        when(harness.search().keywordRetrieve(any())).thenThrow(new IllegalStateException("private failure"));
        assertThatThrownBy(() -> harness.retriever().retrieve("workspace", "query", Set.of()))
                .isInstanceOf(com.noteweave.retrieval.provider.RetrievalProviderException.class)
                .hasMessage("QA recall channels are unavailable");
    }

    @Test
    void businessFailureIsNeverConvertedToProviderDegradation() {
        Harness harness = harness();
        var failure = new com.noteweave.common.BusinessException("ORIGIN_INVALID", "denied",
                org.springframework.http.HttpStatus.FORBIDDEN);
        when(harness.search().vectorRetrieve(any())).thenThrow(failure);
        assertThatThrownBy(() -> harness.retriever().retrieve("workspace", "query", Set.of()))
                .isSameAs(failure);
    }

    private Harness harness() {
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.isEnabled()).thenReturn(true);
        when(embedding.embedQuery(any())).thenReturn(new EmbeddingClient.EmbeddingResult(
                List.of(List.of(1f, 0f, 0f)), "stub-embedding", 3, 1));
        QaHybridSearchPort search = mock(QaHybridSearchPort.class);
        when(search.vectorRetrieve(any())).thenReturn(List.of(hit("only")));
        when(search.keywordRetrieve(any())).thenReturn(List.of());
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        when(hydrator.hydratePassageOwnership(eq("workspace"), any())).thenReturn(Map.of(
                "only", new PassageOwnership("only", "source-only", "snapshot-only", "", "")));
        when(hydrator.hydrateAdjacentPassages(eq("workspace"), any())).thenReturn(Map.of());
        RerankClient rerank = mock(RerankClient.class);
        when(rerank.isEnabled()).thenReturn(true);
        when(rerank.rerank(any(), any(), anyInt())).thenReturn(new RerankClient.RerankResult(
                List.of(new RerankClient.Hit(0, 0.9, 1)), "stub-rerank"));
        QaQueryExpansionService expansion = mock(QaQueryExpansionService.class);
        when(expansion.expand(any())).thenReturn(List.of());
        QaHybridRetriever retriever = new QaHybridRetriever(
                embedding, search, new QaRrfFusionService(), new QaRerankService(rerank),
                hydrator, expansion);
        return new Harness(retriever, search, expansion, rerank);
    }

    private QaSearchHit hit(String id) {
        return new QaSearchHit(id, "source-" + id, "snapshot-" + id, 0,
                id, "heading", "MARKDOWN", "content " + id, 0.9d);
    }

    private record Harness(
            QaHybridRetriever retriever,
            QaHybridSearchPort search,
            QaQueryExpansionService expansion,
            RerankClient rerank
    ) {
    }
}
