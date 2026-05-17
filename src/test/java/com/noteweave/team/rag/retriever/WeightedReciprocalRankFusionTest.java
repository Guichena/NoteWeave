package com.noteweave.team.rag.retriever;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeightedReciprocalRankFusionTest {

    @Test
    void shouldFuseByChunkIdUsingConfiguredWeightsAndRanks() {
        WeightedReciprocalRankFusion fusion = new WeightedReciprocalRankFusion();

        List<RetrievalHit> bm25Hits = List.of(
                hit("BM25", 101L, 11L, 5L, 1L, 0, 1, 12.0d),
                hit("BM25", 102L, 12L, 5L, 1L, 1, 2, 11.0d)
        );
        List<RetrievalHit> vectorHits = List.of(
                hit("Vector", 102L, 12L, 5L, 1L, 1, 1, 0.92d),
                hit("Vector", 103L, 13L, 5L, 1L, 2, 2, 0.87d)
        );
        List<RetrievalHit> wikiHits = List.of(
                hit("Wiki", 103L, 13L, 5L, 1L, 2, 1, 0.88d)
        );

        List<RetrievalHit> fused = fusion.fuse(
                List.of(bm25Hits, vectorHits, wikiHits),
                RrfOptions.builder()
                        .rrfK(60)
                        .weights(Map.of(
                                "BM25", 1.0d,
                                "Vector", 1.0d,
                                "Wiki", 1.3d
                        ))
                        .topK(10)
                        .build()
        );

        assertThat(fused).hasSize(3);
        assertThat(fused)
                .extracting(RetrievalHit::chunkId)
                .containsExactly(103L, 102L, 101L);
        assertThat(fused.get(0).metadata())
                .containsEntry("rrfScore", fused.get(0).score())
                .containsKey("rrfBreakdown");
        assertThat(fused.get(0).metadata().get("rrfBreakdown").toString())
                .contains("Wiki")
                .contains("Vector");
    }

    @Test
    void shouldRespectTopKAfterFusion() {
        WeightedReciprocalRankFusion fusion = new WeightedReciprocalRankFusion();

        List<RetrievalHit> fused = fusion.fuse(
                List.of(
                        List.of(
                                hit("BM25", 101L, 11L, 5L, 1L, 0, 1, 9.0d),
                                hit("BM25", 102L, 12L, 5L, 1L, 1, 2, 8.0d)
                        ),
                        List.of(
                                hit("Vector", 102L, 12L, 5L, 1L, 1, 1, 0.91d),
                                hit("Vector", 103L, 13L, 5L, 1L, 2, 2, 0.84d)
                        )
                ),
                RrfOptions.builder()
                        .rrfK(60)
                        .weights(Map.of(
                                "BM25", 1.0d,
                                "Vector", 1.0d
                        ))
                        .topK(2)
                        .build()
        );

        assertThat(fused).hasSize(2);
        assertThat(fused)
                .extracting(RetrievalHit::chunkId)
                .containsExactly(102L, 101L);
    }

    @Test
    void shouldPreservePrototypeMetadataWhenAddingRrfExplainability() {
        WeightedReciprocalRankFusion fusion = new WeightedReciprocalRankFusion();

        List<RetrievalHit> fused = fusion.fuse(
                List.of(
                        List.of(hit("BM25", 101L, 11L, 5L, 1L, 0, 1, 9.0d))
                ),
                RrfOptions.builder()
                        .rrfK(60)
                        .weights(Map.of("BM25", 1.0d))
                        .topK(1)
                        .build()
        );

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).metadata())
                .containsEntry("rawScore", 9.0d)
                .containsEntry("indexVersion", 1)
                .containsKey("rrfScore")
                .containsKey("rrfBreakdown");
    }

    private RetrievalHit hit(
            String retrieverName,
            Long chunkId,
            Long documentId,
            Long knowledgeBaseId,
            Long spaceId,
            Integer chunkIndex,
            Integer rank,
            Double score
    ) {
        return RetrievalHit.builder()
                .retrieverName(retrieverName)
                .chunkId(chunkId)
                .documentId(documentId)
                .knowledgeBaseId(knowledgeBaseId)
                .spaceId(spaceId)
                .chunkIndex(chunkIndex)
                .documentTitle("Document-" + documentId)
                .content("Content-" + chunkId)
                .score(score)
                .rank(rank)
                .metadata(Map.of(
                        "rawScore", score,
                        "indexVersion", 1
                ))
                .build();
    }
}
