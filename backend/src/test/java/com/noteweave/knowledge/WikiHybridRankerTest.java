package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.noteweave.retrieval.provider.RerankClient;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WikiHybridRankerTest {

    @Test
    void fuseSumsChannelsAndCapsAuthority() {
        WikiHybridRanker.ChannelHit lexical = new WikiHybridRanker.ChannelHit("page", 1, 2, 3, 4);
        WikiHybridRanker.ChannelHit vector = new WikiHybridRanker.ChannelHit("page", 0, 9, 1, 0);

        WikiHybridRanker.Ranked ranked = WikiHybridRanker.fuse(List.of(lexical), List.of(vector)).get(0);

        double authority = Math.min(
                KnowledgeWikiSearchEngine.AUTHORITY_CAP,
                KnowledgeWikiSearchEngine.AUTHORITY_WEIGHT * Math.log1p(12));
        assertThat(ranked.score()).isCloseTo((2.0d / 61.0d) * (1.0d + authority), within(1.0e-12));
        assertThat(ranked.outgoingCount()).isEqualTo(1);
        assertThat(ranked.backlinkCount()).isEqualTo(9);
        assertThat(ranked.citationCount()).isEqualTo(3);
        assertThat(ranked.unresolvedCount()).isEqualTo(4);
    }

    @Test
    void authorityStopsAtCap() {
        WikiHybridRanker.ChannelHit hit = new WikiHybridRanker.ChannelHit("page", 0, 0, 100_000, 0);

        WikiHybridRanker.Ranked ranked = WikiHybridRanker.fuse(List.of(hit), List.of()).get(0);

        assertThat(ranked.score()).isCloseTo(
                (1.0d / 61.0d) * (1.0d + KnowledgeWikiSearchEngine.AUTHORITY_CAP),
                within(1.0e-12));
    }

    @Test
    void relativeCutoffDropsTheLongTail() {
        List<WikiHybridRanker.ChannelHit> lexical = new ArrayList<>();
        for (int index = 0; index < 400; index++) {
            lexical.add(new WikiHybridRanker.ChannelHit("page-" + index, 0, 0, 0, 0));
        }

        List<WikiHybridRanker.Ranked> ranked = WikiHybridRanker.fuse(lexical, List.of());

        assertThat(ranked).hasSizeLessThan(400);
        assertThat(ranked.get(0).itemId()).isEqualTo("page-0");
        assertThat(ranked).extracting(WikiHybridRanker.Ranked::itemId).doesNotContain("page-399");
    }

    @Test
    void rerankReordersTheWindowAndKeepsTheTail() {
        List<WikiHybridRanker.Ranked> fused = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            fused.add(new WikiHybridRanker.Ranked("item-" + index, 1.0d / (index + 1), index, 0, 0, 0));
        }

        List<WikiHybridRanker.Ranked> reranked = WikiHybridRanker.rerank(fused, List.of(
                new RerankClient.Hit(2, 0.9d, 1),
                new RerankClient.Hit(0, 0.4d, 2)));

        assertThat(reranked).extracting(WikiHybridRanker.Ranked::itemId)
                .startsWith("item-2", "item-0", "item-1", "item-3")
                .endsWith("item-23", "item-24");
        assertThat(reranked).hasSize(25);
        assertThat(reranked.get(0).score()).isEqualTo(0.9d);
        assertThat(reranked.get(2).score()).isEqualTo(fused.get(1).score());
        assertThat(reranked.get(20).itemId()).isEqualTo("item-20");
    }

    @Test
    void invalidRerankKeepsCallerOnTheFusedOrder() {
        List<WikiHybridRanker.Ranked> fused = List.of(
                new WikiHybridRanker.Ranked("item-a", 0.2d, 0, 0, 0, 0),
                new WikiHybridRanker.Ranked("item-b", 0.1d, 0, 0, 0, 0));

        assertThat(WikiHybridRanker.rerank(fused, List.of())).isNull();
        assertThat(WikiHybridRanker.rerank(fused, List.of(
                new RerankClient.Hit(0, 0.5d, 1),
                new RerankClient.Hit(0, 0.4d, 2)))).isNull();
        assertThat(WikiHybridRanker.rerank(fused, List.of(
                new RerankClient.Hit(2, 0.5d, 1)))).isNull();
        assertThat(WikiHybridRanker.rerank(List.of(), List.of(new RerankClient.Hit(0, 1.0d, 1))))
                .isEmpty();
    }
}
