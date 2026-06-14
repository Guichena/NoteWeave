package com.noteweave.team.rag.retriever;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.noteweave.embedding.service.EmbeddingClient;
import com.noteweave.personal.claim.service.SearchIndexClaimSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClaimRetrieverTest {

    @Mock
    private SearchIndexClaimSupport searchIndexClaimSupport;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private WeightedReciprocalRankFusion fusion;

    private ClaimRetriever claimRetriever;

    @BeforeEach
    void setUp() {
        claimRetriever = new ClaimRetriever(searchIndexClaimSupport, embeddingClient, fusion);
    }

    @Test
    void shouldReturnEmptyWhenQuestionContextIsMissing() {
        assertThat(claimRetriever.retrieve(new TeamRetrievalQuery(7L, 1L, List.of(10L), "GraphRAG", 5, true, null))).isEmpty();
    }

    @Test
    void shouldRecallClaimsFromKeywordAndVectorMatches() {
        TeamRetrievalQuery query = new TeamRetrievalQuery(7L, 1L, List.of(10L), "GraphRAG alternatives", 5, true, 88L);
        SearchIndexClaimSupport.ClaimSearchHit keywordHit = new SearchIndexClaimSupport.ClaimSearchHit(
                123L,
                77L,
                "Is GraphRAG worth it?",
                "GraphRAG is too heavy for the MVP.",
                "The operations cost is too high.",
                "CONCLUSION",
                "SUPPORTED",
                0.82d,
                List.of("GraphRAG"),
                3.1d
        );
        SearchIndexClaimSupport.ClaimSearchHit vectorHit = new SearchIndexClaimSupport.ClaimSearchHit(
                123L,
                77L,
                "Is GraphRAG worth it?",
                "GraphRAG is too heavy for the MVP.",
                "The operations cost is too high.",
                "CONCLUSION",
                "SUPPORTED",
                0.82d,
                List.of("GraphRAG"),
                2.6d
        );
        RetrievalHit fusedHit = RetrievalHit.builder()
                .retrieverName("ClaimKeyword")
                .chunkId(-1_000_000_000_123L)
                .documentId(123L)
                .knowledgeBaseId(0L)
                .spaceId(1L)
                .chunkIndex(0)
                .documentTitle("Claim - Is GraphRAG worth it?")
                .content("[CONCLUSION/SUPPORTED] GraphRAG is too heavy for the MVP.")
                .score(4.0d)
                .rank(null)
                .metadata(Map.of("sourceType", "CLAIM", "sourceId", 123L, "indexVersion", 0))
                .build();

        given(searchIndexClaimSupport.search(7L, 1L, "GraphRAG alternatives", 5, 88L)).willReturn(List.of(keywordHit));
        given(embeddingClient.embedTexts(List.of("GraphRAG alternatives"))).willReturn(List.of(new float[]{0.1f, 0.2f}));
        given(searchIndexClaimSupport.searchByVector(7L, 1L, new float[]{0.1f, 0.2f}, 5, 88L)).willReturn(List.of(vectorHit));
        given(fusion.fuse(any(), any())).willReturn(List.of(fusedHit));

        List<RetrievalHit> hits = claimRetriever.retrieve(query);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).retrieverName()).isEqualTo("Claim");
        assertThat(hits.get(0).metadata()).containsEntry("sourceType", "CLAIM");
        assertThat(hits.get(0).documentTitle()).contains("GraphRAG");
    }
}
