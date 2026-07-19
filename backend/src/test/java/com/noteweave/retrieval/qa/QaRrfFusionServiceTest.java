package com.noteweave.retrieval.qa;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.retrieval.qa.QaHybridSearchPort.QaSearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;

class QaRrfFusionServiceTest {
    private final QaRrfFusionService fusion = new QaRrfFusionService();

    @Test
    void appliesWeightedRrfWithoutAddingRawBm25AndCosineScores() {
        QaSearchHit semanticOnly = hit("semantic", 0.91);
        QaSearchHit overlapVector = hit("overlap", 0.80);
        QaSearchHit overlapKeyword = hit("overlap", 14.7);
        QaSearchHit exactOnly = hit("exact", 18.2);

        List<QaRrfFusionService.FusedHit> result = fusion.fuse(
                List.of(semanticOnly, overlapVector),
                List.of(overlapKeyword, exactOnly),
                60, 0.7, 0.3, 10);

        assertThat(result).extracting(QaRrfFusionService.FusedHit::chunkId)
                .containsExactly("overlap", "semantic", "exact");
        QaRrfFusionService.FusedHit overlap = result.get(0);
        assertThat(overlap.rrfScore())
                .isEqualTo(0.7 / 62.0 + 0.3 / 61.0);
        assertThat(overlap.vectorScore()).isEqualTo(0.80);
        assertThat(overlap.keywordScore()).isEqualTo(14.7);
        assertThat(overlap.matchedChannels()).containsExactly("vector", "keyword");
    }

    private QaSearchHit hit(String id, double score) {
        return new QaSearchHit(
                id, "source-" + id, "snapshot-1", 0, "Title", "Heading", "PDF", "Content", score);
    }
}
