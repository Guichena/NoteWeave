package com.noteweave.team.rag.retriever;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.team.rag.config.RagProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HybridRetrieverTest {

    @Mock
    private Bm25Retriever bm25Retriever;

    @Mock
    private VectorRetriever vectorRetriever;

    @Mock
    private com.noteweave.team.wiki.service.WikiRetriever wikiRetriever;

    @Mock
    private WeightedReciprocalRankFusion fusion;

    private HybridRetriever hybridRetriever;

    @BeforeEach
    void setUp() {
        hybridRetriever = new HybridRetriever(
                bm25Retriever,
                vectorRetriever,
                wikiRetriever,
                fusion,
                new RagProperties(
                        new RagProperties.Retrieval(RetrievalMode.HYBRID, 5, 2, 4_000, 1_200, 0.0d, 1.0d, 1.0d, 1.3d, 60),
                        new RagProperties.Prompt("No evidence")
                )
        );
    }

    @Test
    void shouldFallbackToBm25AndWikiWhenVectorEmbeddingFails() {
        TeamRetrievalQuery query = new TeamRetrievalQuery(1L, 10L, List.of(20L), "rollback", 5, true);
        RetrievedChunk bm25Chunk = new RetrievedChunk(101L, 1001L, 20L, 10L, "DOCUMENT", 1001L, 2, 0, "Ops Runbook", "Rollback rehearsal is required.", 9.0d, 1, 0, 32, "2");
        RetrievalHit wikiHit = RetrievalHit.builder()
                .retrieverName("Wiki")
                .chunkId(-501L)
                .documentId(501L)
                .knowledgeBaseId(0L)
                .spaceId(10L)
                .chunkIndex(0)
                .documentTitle("Wiki Rollback")
                .content("Wiki content")
                .score(1.5d)
                .rank(1)
                .metadata(Map.of(
                        "sourceType", "WIKI_PAGE",
                        "sourceId", 501L,
                        "publishedVersionId", 77L,
                        "indexVersion", 0
                ))
                .build();
        RetrievalHit fusedHit = RetrievalHit.builder()
                .retrieverName("BM25")
                .chunkId(101L)
                .documentId(1001L)
                .knowledgeBaseId(20L)
                .spaceId(10L)
                .chunkIndex(0)
                .documentTitle("Ops Runbook")
                .content("Rollback rehearsal is required.")
                .score(3.0d)
                .rank(1)
                .metadata(Map.of("indexVersion", 2))
                .build();

        when(bm25Retriever.retrieveChunks(query)).thenReturn(List.of(bm25Chunk));
        when(vectorRetriever.retrieve(query)).thenThrow(new BusinessException(ErrorCode.LLM_CONFIG_MISSING, "embedding key missing"));
        when(wikiRetriever.retrieve(query)).thenReturn(List.of(wikiHit));
        when(fusion.fuse(any(), any())).thenReturn(List.of(fusedHit, wikiHit));

        HybridRetriever.HybridRetrievalResult result = hybridRetriever.retrieve(query, RetrievalMode.HYBRID);

        assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.bm25Count()).isEqualTo(1);
        assertThat(result.vectorCount()).isEqualTo(0);
        assertThat(result.fusedHits()).containsExactly(fusedHit, wikiHit);
        assertThat(result.traceJson()).contains("\"bm25\":1").contains("\"wiki\":1");
        verify(fusion).fuse(
                argThat(hitLists -> hitLists.size() == 3
                        && hitLists.get(0).size() == 1
                        && hitLists.get(1).isEmpty()
                        && hitLists.get(2).size() == 1),
                any()
        );
    }
}
