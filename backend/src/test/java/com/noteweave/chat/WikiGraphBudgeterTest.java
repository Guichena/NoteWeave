package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.knowledge.KnowledgePageHit;
import com.noteweave.knowledge.WikiPageContext;
import com.noteweave.knowledge.WikiLinkResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class WikiGraphBudgeterTest {

    private final WikiGraphBudgeter budgeter = new WikiGraphBudgeter();

    @Test
    void shouldKeepSeedRelationsAndCapUniqueExpandedNodesAcrossContexts() {
        WikiPageContext first = context(
                "root-a",
                List.of(
                        outgoing("root-a", "node-x", "X"),
                        outgoing("root-a", "node-y", "Y"),
                        outgoing("root-a", "root-b", "B")
                ),
                List.of(backlink("node-z", "root-a", "Z"))
        );
        WikiPageContext second = context(
                "root-b",
                List.of(
                        outgoing("root-b", "node-x", "X"),
                        outgoing("root-b", "node-w", "W")
                ),
                List.of()
        );

        WikiGraphBudgeter.Result result = budgeter.apply(
                List.of(first, second), new RetrievalPlan.Budget(5, 20_000, 1, 2));

        assertThat(result.graphHopsUsed()).isEqualTo(1);
        assertThat(result.expandedNodeCount()).isEqualTo(2);
        assertThat(result.graphEdgeCount()).isEqualTo(4);
        assertThat(result.graphCharacterCount()).isPositive().isLessThanOrEqualTo(4_000);
        assertThat(result.contexts().get(0).outgoingLinks())
                .extracting(WikiLinkResponse::targetItemId)
                .containsExactly("node-x", "node-y", "root-b");
        assertThat(result.contexts().get(0).backlinks()).isEmpty();
        assertThat(result.contexts().get(1).outgoingLinks())
                .extracting(WikiLinkResponse::targetItemId)
                .containsExactly("node-x");
    }

    @Test
    void shouldRemoveAllGraphEdgesWhenHopBudgetIsZero() {
        WikiPageContext context = context(
                "root",
                List.of(outgoing("root", "node", "Node")),
                List.of(backlink("back", "root", "Back"))
        );

        WikiGraphBudgeter.Result result = budgeter.apply(
                List.of(context), new RetrievalPlan.Budget(5, 20_000, 0, 30));

        assertThat(result.graphHopsUsed()).isZero();
        assertThat(result.expandedNodeCount()).isZero();
        assertThat(result.graphEdgeCount()).isZero();
        assertThat(result.graphCharacterCount()).isZero();
        assertThat(result.contexts()).singleElement().satisfies(item -> {
            assertThat(item.outgoingLinks()).isEmpty();
            assertThat(item.backlinks()).isEmpty();
        });
    }

    @Test
    void shouldCapGraphEdgesIndependentlyFromExpandedNodes() {
        WikiPageContext context = context(
                "root",
                List.of(
                        outgoing("root", "node-a", "A"),
                        outgoing("root", "node-b", "B"),
                        outgoing("root", "node-c", "C")
                ),
                List.of()
        );

        WikiGraphBudgeter.Result result = budgeter.apply(
                List.of(context), new RetrievalPlan.Budget(5, 20_000, 1, 30, 2, 4_000));

        assertThat(result.graphEdgeCount()).isEqualTo(2);
        assertThat(result.expandedNodeCount()).isEqualTo(2);
        assertThat(result.contexts().get(0).outgoingLinks())
                .extracting(WikiLinkResponse::targetItemId)
                .containsExactly("node-a", "node-b");
    }

    @Test
    void shouldCapRenderedGraphCharactersDeterministically() {
        WikiPageContext context = context(
                "root",
                List.of(
                        outgoing("root", "node-a", "A"),
                        outgoing("root", "node-b", "B")
                ),
                List.of()
        );

        WikiGraphBudgeter.Result result = budgeter.apply(
                List.of(context), new RetrievalPlan.Budget(5, 20_000, 1, 30, 10, 20));

        assertThat(result.graphEdgeCount()).isEqualTo(1);
        assertThat(result.graphCharacterCount()).isEqualTo(20);
        assertThat(result.contexts().get(0).outgoingLinks())
                .extracting(WikiLinkResponse::targetItemId)
                .containsExactly("node-a");
    }

    private WikiPageContext context(
            String itemId,
            List<WikiLinkResponse> outgoing,
            List<WikiLinkResponse> backlinks
    ) {
        return new WikiPageContext(
                new KnowledgePageHit(
                        itemId, "version-" + itemId, 1, itemId,
                        "content", "summary", 1),
                outgoing,
                backlinks,
                List.of()
        );
    }

    private WikiLinkResponse outgoing(String source, String target, String title) {
        return new WikiLinkResponse(source, target, title, "WIKI_LINK", "RESOLVED", 1);
    }

    private WikiLinkResponse backlink(String source, String target, String sourceTitle) {
        return new WikiLinkResponse(source, target, sourceTitle, "WIKI_LINK", "RESOLVED", 1);
    }
}
