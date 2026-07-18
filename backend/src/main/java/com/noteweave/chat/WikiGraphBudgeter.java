package com.noteweave.chat;

import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.knowledge.WikiPageContext;
import com.noteweave.knowledge.WikiLinkResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class WikiGraphBudgeter {

    public Result apply(List<WikiPageContext> contexts, RetrievalPlan.Budget budget) {
        List<WikiPageContext> roots = contexts == null ? List.of() : List.copyOf(contexts);
        int maxHops = budget == null ? 0 : Math.max(0, budget.maxGraphHops());
        int maxExpandedNodes = budget == null ? 0 : Math.max(0, budget.maxGraphNodes());
        int maxEdges = budget == null ? 0 : Math.max(0, budget.maxGraphEdges());
        int maxCharacters = budget == null ? 0 : Math.max(0, budget.maxGraphCharacters());
        if (roots.isEmpty()) {
            return new Result(
                    List.of(), 0, 0, 0, 0,
                    maxHops, maxExpandedNodes, maxEdges, maxCharacters);
        }
        if (maxHops == 0) {
            return new Result(roots.stream()
                    .map(context -> withoutLinks(context))
                    .toList(), 0, 0, 0, 0,
                    maxHops, maxExpandedNodes, maxEdges, maxCharacters);
        }

        Set<String> seedNodes = new LinkedHashSet<>();
        roots.forEach(context -> seedNodes.add(context.page().itemId()));
        Set<String> expandedNodes = new LinkedHashSet<>();
        GraphUsage usage = new GraphUsage(maxEdges, maxCharacters);
        List<WikiPageContext> budgeted = new ArrayList<>();
        for (WikiPageContext context : roots) {
            List<WikiLinkResponse> outgoing = admitLinks(
                    context.outgoingLinks(), false, seedNodes, expandedNodes,
                    maxExpandedNodes, usage);
            List<WikiLinkResponse> backlinks = admitLinks(
                    context.backlinks(), true, seedNodes, expandedNodes,
                    maxExpandedNodes, usage);
            budgeted.add(new WikiPageContext(
                    context.page(), outgoing, backlinks, context.citations()));
        }
        return new Result(
                budgeted,
                usage.edgeCount() > 0 ? 1 : 0,
                expandedNodes.size(),
                usage.edgeCount(),
                usage.characterCount(),
                maxHops,
                maxExpandedNodes,
                maxEdges,
                maxCharacters
        );
    }

    private WikiPageContext withoutLinks(WikiPageContext context) {
        return new WikiPageContext(
                context.page(), List.of(), List.of(), context.citations());
    }

    private List<WikiLinkResponse> admitLinks(
            List<WikiLinkResponse> links,
            boolean backlink,
            Set<String> seedNodes,
            Set<String> expandedNodes,
            int maxExpandedNodes,
            GraphUsage usage
    ) {
        List<WikiLinkResponse> admitted = new ArrayList<>();
        for (WikiLinkResponse link : links) {
            int characterCost = linkCharacterCost(link);
            if (!usage.canAdmit(characterCost)) {
                continue;
            }
            String nodeKey = nodeKey(link, backlink);
            boolean existingNode = seedNodes.contains(nodeKey) || expandedNodes.contains(nodeKey);
            if (!existingNode && expandedNodes.size() >= maxExpandedNodes) {
                continue;
            }
            if (!existingNode) {
                expandedNodes.add(nodeKey);
            }
            admitted.add(link);
            usage.admit(characterCost);
        }
        return List.copyOf(admitted);
    }

    private int linkCharacterCost(WikiLinkResponse link) {
        return 10
                + text(link.targetTitle()).length()
                + text(link.relationStatus()).length()
                + Integer.toString(link.mentionCount()).length();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private String nodeKey(WikiLinkResponse link, boolean backlink) {
        String id = backlink ? link.sourceItemId() : link.targetItemId();
        if (id != null && !id.isBlank()) {
            return id;
        }
        return (backlink ? "backlink-title:" : "outgoing-title:")
                + (link.targetTitle() == null ? "" : link.targetTitle());
    }

    public record Result(
            List<WikiPageContext> contexts,
            int graphHopsUsed,
            int expandedNodeCount,
            int graphEdgeCount,
            int graphCharacterCount,
            int graphHopLimit,
            int expandedNodeLimit,
            int graphEdgeLimit,
            int graphCharacterLimit
    ) {
        public Result {
            contexts = contexts == null ? List.of() : List.copyOf(contexts);
        }
    }

    private static final class GraphUsage {
        private final int edgeLimit;
        private final int characterLimit;
        private int edgeCount;
        private int characterCount;

        private GraphUsage(int edgeLimit, int characterLimit) {
            this.edgeLimit = edgeLimit;
            this.characterLimit = characterLimit;
        }

        private boolean canAdmit(int characters) {
            return edgeCount < edgeLimit
                    && characterCount + Math.max(0, characters) <= characterLimit;
        }

        private void admit(int characters) {
            edgeCount++;
            characterCount += Math.max(0, characters);
        }

        private int edgeCount() {
            return edgeCount;
        }

        private int characterCount() {
            return characterCount;
        }
    }
}
