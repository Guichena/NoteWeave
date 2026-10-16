package com.noteweave.retrieval.lexical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Bm25fScorerTest {
    private final Bm25fScorer scorer = new Bm25fScorer();

    @Test
    void singleTermScoreMatchesBm25fSaturation() {
        List<Bm25fScorer.Hit> hits = scorer.score(
                Bm25fScorer.DEFAULT_K1,
                List.of(new Bm25fScorer.FieldSpec("title", 1.0d, Bm25fScorer.DEFAULT_B)),
                List.of(new Bm25fScorer.Document("doc", Map.of("title", "alpha"))),
                Set.of("alpha"),
                Map.of(),
                Map.of());

        double idf = Math.log(1.0d + 0.5d / 1.5d);
        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.id()).isEqualTo("doc");
            assertThat(hit.score()).isCloseTo(idf / (Bm25fScorer.DEFAULT_K1 + 1.0d), within(1e-9));
            assertThat(hit.matchedTerms()).containsExactly("alpha");
            assertThat(hit.matchedFields()).containsExactly("title");
        });
    }

    @Test
    void titleFieldOutranksBodyOnlyAndMustTermRaisesTheSameField() {
        List<Bm25fScorer.FieldSpec> fields = List.of(
                new Bm25fScorer.FieldSpec("title", 3.0d, Bm25fScorer.DEFAULT_B),
                new Bm25fScorer.FieldSpec("content", 1.0d, Bm25fScorer.DEFAULT_B));
        List<Bm25fScorer.Document> documents = List.of(
                new Bm25fScorer.Document("title", Map.of("title", "缓存一致性", "content", "其他正文")),
                new Bm25fScorer.Document("body", Map.of("title", "其他标题", "content", "缓存一致性")));

        List<Bm25fScorer.Hit> plain = scorer.score(
                Bm25fScorer.DEFAULT_K1, fields, documents, Set.of("缓存", "存一", "一致"), Map.of(), Map.of());
        double titleScore = scoreOf(plain, "title");
        double bodyScore = scoreOf(plain, "body");
        assertThat(titleScore).isGreaterThan(bodyScore);

        List<Bm25fScorer.Hit> boosted = scorer.score(
                Bm25fScorer.DEFAULT_K1,
                fields,
                documents,
                Set.of("缓存", "存一", "一致"),
                Map.of("缓存", Bm25fScorer.FIELD_TERM_BOOST, "存一", Bm25fScorer.FIELD_TERM_BOOST,
                        "一致", Bm25fScorer.FIELD_TERM_BOOST),
                Map.of("title", Set.of("缓存", "存一", "一致")));
        assertThat(scoreOf(boosted, "title")).isGreaterThan(titleScore);
    }

    @Test
    void emptyQueryOrCorpusProducesNoHits() {
        assertThat(scorer.score(1.2d, List.of(new Bm25fScorer.FieldSpec("title", 1, 0.75d)),
                List.of(), Set.of("alpha"), Map.of(), Map.of())).isEmpty();
        assertThat(scorer.score(1.2d, List.of(new Bm25fScorer.FieldSpec("title", 1, 0.75d)),
                List.of(new Bm25fScorer.Document("doc", Map.of("title", "alpha"))),
                Set.of(), Map.of(), Map.of())).isEmpty();
    }

    private double scoreOf(List<Bm25fScorer.Hit> hits, String id) {
        return hits.stream().filter(hit -> hit.id().equals(id)).findFirst().orElseThrow().score();
    }
}
