package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryRelevanceScorerTest {

    private final MemoryRelevanceScorer scorer = new MemoryRelevanceScorer();

    @Test
    void blankQueryScoresZeroWithoutAskingForVectors() {
        MemoryRelevanceScorer.Result result = scorer.score("  ", List.of(
                new MemoryRelevanceScorer.Item("page", "引用时保留页码", List.of())), null);

        assertThat(result.relevance()).containsEntry("page", 0d);
        assertThat(result.degradationReasons()).containsExactly("MEMORY_RELEVANCE_QUERY_ABSENT");
    }

    @Test
    void sharedBigramOutranksUnrelatedTextWhenVectorsAreUnavailable() {
        MemoryRelevanceScorer.Result result = scorer.score("论文页码在哪里", List.of(
                new MemoryRelevanceScorer.Item("hit", "引用时保留页码", List.of()),
                new MemoryRelevanceScorer.Item("miss", "回答保持简洁", List.of())
        ), null);

        assertThat(result.degradationReasons()).containsExactly("MEMORY_RELEVANCE_VECTOR_UNAVAILABLE");
        assertThat(result.relevance().get("hit")).isGreaterThan(0d);
        assertThat(result.relevance().get("miss")).isZero();
    }

    @Test
    void emptyItemsDoNotReportVectorDegradation() {
        MemoryRelevanceScorer.Result result = scorer.score("页码", List.of(), null);

        assertThat(result.relevance()).isEmpty();
        assertThat(result.degradationReasons()).isEmpty();
    }
}
