package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.knowledge.KnowledgePageHit;
import com.noteweave.knowledge.WikiResolvedLink;
import com.noteweave.knowledge.WikiPageContext;
import com.noteweave.knowledge.WikiPersonalizedPageRank;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WikiGraphBudgeterTest {

    @Test
    void shouldRemoveAllGraphEdgesWhenHopBudgetIsZero() {
        WikiPageContext context = context("root", "Root", "summary", 1);

        WikiGraphBudgeter.Result result = new WikiGraphBudgeter().apply(
                List.of(context), new RetrievalPlan.Budget(5, 20_000, 0, 30));

        assertThat(result.graphHopsUsed()).isZero();
        assertThat(result.expandedNodeCount()).isZero();
        assertThat(result.graphPaths()).isEmpty();
        assertThat(result.contexts()).singleElement().satisfies(item -> {
            assertThat(item.outgoingLinks()).isEmpty();
            assertThat(item.backlinks()).isEmpty();
        });
    }

    @Test
    void shouldStopAtThreeHopsAndRecordTheShortestTitlePath() {
        RecordingSource source = new RecordingSource(List.of(
                edge("A", "Alpha", "B", "Beta"),
                edge("B", "Beta", "C", "Gamma"),
                edge("C", "Gamma", "D", "Delta"),
                edge("D", "Delta", "E", "Epsilon")
        ), Map.of());
        Map<String, WikiPageContext> pages = Map.of(
                "B", context("B", "Beta", "b", 0),
                "C", context("C", "Gamma", "c", 0),
                "D", context("D", "Delta", "d", 0),
                "E", context("E", "Epsilon", "e", 0)
        );

        WikiGraphBudgeter.Result result = new WikiGraphBudgeter().apply(
                List.of(context("A", "Alpha", "seed", 10)),
                new RetrievalPlan.Budget(40, 20_000, 9, 30, 60, 4_000),
                source,
                ids -> load(pages, ids));

        assertThat(result.graphHopLimit()).isEqualTo(3);
        assertThat(result.graphHopsUsed()).isEqualTo(3);
        assertThat(result.contexts()).extracting(item -> item.page().itemId())
                .containsExactly("A", "B", "C", "D");
        assertThat(result.graphPaths().get("D")).containsExactly("Alpha", "Beta", "Gamma", "Delta");
        assertThat(result.graphEdgeCount()).isEqualTo(1 + 2 + 3);
        assertThat(result.graphCharacterCount()).isEqualTo(3);
    }

    @Test
    void shouldPreferTheLowDegreeNeighborWhenOnlyOneNodeFits() {
        RecordingSource source = new RecordingSource(List.of(
                edge("A", "Alpha", "B", "Beta"),
                edge("A", "Alpha", "H", "Hub")
        ), Map.of("B", 1, "H", 100));
        Map<String, WikiPageContext> pages = Map.of(
                "B", context("B", "Beta", "short", 0),
                "H", context("H", "Hub", "short", 0)
        );

        WikiGraphBudgeter.Result result = new WikiGraphBudgeter().apply(
                List.of(context("A", "Alpha", "seed", 10)),
                new RetrievalPlan.Budget(40, 20_000, 3, 1, 60, 4_000),
                source,
                ids -> load(pages, ids));

        assertThat(result.expandedNodeCount()).isEqualTo(1);
        assertThat(result.graphPaths()).containsOnlyKeys("B");
        assertThat(result.graphPaths().get("B")).containsExactly("Alpha", "Beta");
    }

    @Test
    void shouldSkipAnExpandedPageThatExceedsTheCharacterBudget() {
        RecordingSource source = new RecordingSource(List.of(
                edge("A", "Alpha", "B", "Beta"),
                edge("A", "Alpha", "H", "Hub")
        ), Map.of("B", 1, "H", 100));
        Map<String, WikiPageContext> pages = Map.of(
                "B", context("B", "Beta", "abcd", 0),
                "H", context("H", "Hub", "this is long", 0)
        );

        WikiGraphBudgeter.Result result = new WikiGraphBudgeter().apply(
                List.of(context("A", "Alpha", "seed", 10)),
                new RetrievalPlan.Budget(40, 20_000, 3, 30, 60, 4),
                source,
                ids -> load(pages, ids));

        assertThat(result.graphPaths()).containsOnlyKeys("B");
        assertThat(result.graphCharacterCount()).isEqualTo(4);
    }

    @Test
    void shouldRejectAFartherPageWhenTheEdgeBudgetOnlyCoversOneHop() {
        RecordingSource source = new RecordingSource(List.of(
                edge("A", "Alpha", "B", "Beta"),
                edge("B", "Beta", "C", "Gamma")
        ), Map.of());
        Map<String, WikiPageContext> pages = Map.of(
                "B", context("B", "Beta", "b", 0),
                "C", context("C", "Gamma", "c", 0)
        );

        WikiGraphBudgeter.Result result = new WikiGraphBudgeter().apply(
                List.of(context("A", "Alpha", "seed", 10)),
                new RetrievalPlan.Budget(40, 20_000, 3, 30, 1, 4_000),
                source,
                ids -> load(pages, ids));

        assertThat(result.graphPaths()).containsOnlyKeys("B");
        assertThat(result.graphEdgeCount()).isEqualTo(1);
        assertThat(result.graphHopsUsed()).isEqualTo(1);
    }

    @Test
    void shouldLoadAdjacencyInBatchesOfFifty() {
        List<WikiPageContext> seeds = new ArrayList<>();
        for (int index = 0; index < 51; index++) {
            seeds.add(context("s" + index, "S" + index, "s", 1));
        }
        List<Integer> batchSizes = new ArrayList<>();
        WikiPersonalizedPageRank.AdjacencySource source = new WikiPersonalizedPageRank.AdjacencySource() {
            @Override
            public List<WikiResolvedLink> linksTouching(List<String> itemIds) {
                batchSizes.add(itemIds.size());
                return List.of();
            }

            @Override
            public Map<String, Integer> inDegrees(List<String> itemIds) {
                return Map.of();
            }
        };

        new WikiGraphBudgeter().apply(
                seeds,
                new RetrievalPlan.Budget(80, 20_000, 3, 30, 60, 4_000),
                source,
                ids -> List.of());

        assertThat(batchSizes).containsExactly(50, 1);
    }

    @Test
    void twoNodeWalkConvergesToTheRestartFormula() {
        WikiPersonalizedPageRank.Exploration exploration = WikiPersonalizedPageRank.expand(
                List.of(new WikiPersonalizedPageRank.Seed("A", "Alpha", 1)),
                3,
                new RecordingSource(List.of(edge("A", "Alpha", "B", "Beta")), Map.of("B", 1)));

        assertThat(exploration.pageRank().get("A")).isCloseTo(0.588235, within(1.0e-3));
        assertThat(exploration.pageRank().get("B")).isCloseTo(0.411765, within(1.0e-3));
        assertThat(exploration.expanded()).singleElement().satisfies(page -> {
            assertThat(page.itemId()).isEqualTo("B");
            assertThat(page.hops()).isEqualTo(1);
            // 入度为 1 时除数是 ln(2)，小于 1，调整分高于原始 PageRank。
            assertThat(page.adjustedScore()).isCloseTo(page.pageRank() / Math.log(2), within(1.0e-9));
        });
    }

    private List<WikiPageContext> load(Map<String, WikiPageContext> pages, List<String> ids) {
        List<WikiPageContext> loaded = new ArrayList<>();
        for (String id : ids) {
            WikiPageContext page = pages.get(id);
            if (page != null) {
                loaded.add(page);
            }
        }
        return loaded;
    }

    private WikiPageContext context(String itemId, String title, String summary, int score) {
        return new WikiPageContext(
                new KnowledgePageHit(itemId, "version-" + itemId, 1, title, "content", summary, score),
                List.of(), List.of(), List.of());
    }

    private WikiResolvedLink edge(String source, String sourceTitle, String target, String targetTitle) {
        return new WikiResolvedLink(source, target, sourceTitle, targetTitle, 1);
    }

    private static final class RecordingSource implements WikiPersonalizedPageRank.AdjacencySource {
        private final List<WikiResolvedLink> edges;
        private final Map<String, Integer> degrees;

        private RecordingSource(List<WikiResolvedLink> edges, Map<String, Integer> degrees) {
            this.edges = edges;
            this.degrees = degrees;
        }

        @Override
        public List<WikiResolvedLink> linksTouching(List<String> itemIds) {
            return edges.stream()
                    .filter(edge -> itemIds.contains(edge.sourceItemId()) || itemIds.contains(edge.targetItemId()))
                    .toList();
        }

        @Override
        public Map<String, Integer> inDegrees(List<String> itemIds) {
            Map<String, Integer> selected = new HashMap<>();
            for (String itemId : itemIds) {
                if (degrees.containsKey(itemId)) {
                    selected.put(itemId, degrees.get(itemId));
                }
            }
            return selected;
        }
    }
}
