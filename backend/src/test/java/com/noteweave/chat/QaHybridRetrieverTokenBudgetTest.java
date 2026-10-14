package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.answer.strategy.RetrievalExecutionTrace.RankedCandidateTrace;
import com.noteweave.chat.QaPassageRetriever.RetrievedChunk;
import com.noteweave.chat.RetrievalHydrator.AdjacentPassage;
import com.noteweave.chat.RetrievalHydrator.PassageOwnership;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import com.noteweave.retrieval.RetrievalChannels;
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

/** V3 检索：相邻段落按窗口截断、已选中的相邻段落不重复拼入、候选排序写入追踪。 */
class QaHybridRetrieverTokenBudgetTest {

    // 前一段结尾带空行，截断后应合并为单个换行，判定时才能识别相邻段落块的边界
    private static final String LONG_PREVIOUS = "前".repeat(700) + "\n\n前段结尾";
    private static final String NEXT_CONTENT = "后段开头" + "后".repeat(700);

    @Test
    void v3TrimsAdjacentContextAndSkipsNeighboursThatAreSelectedThemselves() {
        QaHybridRetriever.HybridResult result = retriever().retrieve(
                "workspace", RetrievalChannels.same("question"), Set.of(), QaRetrievalStrategyProfile.V3);

        assertThat(result.chunks()).extracting(RetrievedChunk::chunkId).contains("a", "b");
        RetrievedChunk anchor = result.chunks().stream()
                .filter(chunk -> chunk.chunkId().equals("a")).findFirst().orElseThrow();
        assertThat(anchor.content())
                .contains("content a", "前段结尾", "…")
                .doesNotContain("前".repeat(400))
                .doesNotContain("后段开头");
        assertThat(anchor.matchReason()).contains("adjacent-context:z").doesNotContain("|b");
        assertThat(EvidenceSufficiencyJudge.hitText(anchor.content())).startsWith("content a");
        assertThat(result.measurements()).containsEntry("evidence_token_budget", 8_000L);

        assertThat(result.rankedCandidates()).hasSize(3);
        assertThat(result.rankedCandidates()).extracting(RankedCandidateTrace::rank).containsExactly(1, 2, 3);
        assertThat(result.rankedCandidates()).allSatisfy(candidate -> {
            assertThat(candidate.evidenceId()).startsWith("passage:");
            assertThat(candidate.excerpt()).startsWith("content ");
        });
        assertThat(result.rankedCandidates()).filteredOn(RankedCandidateTrace::selected)
                .hasSize(result.chunks().size());
    }

    @Test
    void v2KeepsTheFullAdjacentContext() {
        QaHybridRetriever.HybridResult result = retriever().retrieve(
                "workspace", RetrievalChannels.same("question"), Set.of(), QaRetrievalStrategyProfile.V2);

        RetrievedChunk anchor = result.chunks().stream()
                .filter(chunk -> chunk.chunkId().equals("a")).findFirst().orElseThrow();
        assertThat(anchor.content()).contains(LONG_PREVIOUS, NEXT_CONTENT);
        assertThat(result.measurements()).doesNotContainKey("evidence_token_budget");
    }

    private QaHybridRetriever retriever() {
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.isEnabled()).thenReturn(true);
        when(embedding.embedQuery(any())).thenReturn(new EmbeddingClient.EmbeddingResult(
                List.of(List.of(1f, 0f, 0f)), "stub-embedding", 3, 1));
        QaHybridSearchPort search = mock(QaHybridSearchPort.class);
        when(search.vectorRetrieve(any())).thenReturn(List.of(
                hit("a", 1, 0.9d), hit("b", 2, 0.8d), hit("c", 7, 0.7d)));
        when(search.keywordRetrieve(any())).thenReturn(List.of());
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        when(hydrator.hydratePassageOwnership(eq("workspace"), any())).thenReturn(Map.of(
                "a", new PassageOwnership("a", "source", "snapshot", "", ""),
                "b", new PassageOwnership("b", "source", "snapshot", "", ""),
                "c", new PassageOwnership("c", "source", "snapshot", "", "")));
        when(hydrator.hydrateAdjacentPassages(eq("workspace"), any())).thenReturn(Map.of(
                "a", List.of(
                        new AdjacentPassage("a", 1, "z", 0, "", LONG_PREVIOUS),
                        new AdjacentPassage("a", 1, "b", 2, "", NEXT_CONTENT))));
        RerankClient rerank = mock(RerankClient.class);
        when(rerank.isEnabled()).thenReturn(false);
        QaQueryExpansionService expansion = mock(QaQueryExpansionService.class);
        when(expansion.expand(any())).thenReturn(List.of());
        return new QaHybridRetriever(
                embedding, search, new QaRrfFusionService(), new QaRerankService(rerank),
                hydrator, expansion);
    }

    private QaSearchHit hit(String id, int chunkNo, double score) {
        return new QaSearchHit(id, "source", "snapshot", chunkNo,
                id, "heading", "MARKDOWN", "content " + id + " 独特内容" + id.repeat(20), score);
    }
}
