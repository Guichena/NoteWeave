package com.noteweave.chat;

import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.knowledge.KnowledgePageHit;
import com.noteweave.knowledge.WikiPageContext;
import com.noteweave.knowledge.WikiPersonalizedPageRank;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class WikiGraphBudgeter {

    private static final int PAGE_LOAD_BATCH = 20;

    public Result apply(List<WikiPageContext> contexts, RetrievalPlan.Budget budget) {
        return apply(contexts, budget, null, null);
    }

    public Result apply(
            List<WikiPageContext> contexts,
            RetrievalPlan.Budget budget,
            WikiPersonalizedPageRank.AdjacencySource adjacency,
            PageSource pages
    ) {
        List<WikiPageContext> roots = contexts == null ? List.of() : List.copyOf(contexts);
        int requestedHops = budget == null ? 0 : Math.max(0, budget.maxGraphHops());
        int maxHops = Math.min(requestedHops, WikiPersonalizedPageRank.MAX_HOPS);
        int maxExpandedNodes = budget == null ? 0 : Math.max(0, budget.maxGraphNodes());
        int maxEdges = budget == null ? 0 : Math.max(0, budget.maxGraphEdges());
        int maxCharacters = budget == null ? 0 : Math.max(0, budget.maxGraphCharacters());
        if (roots.isEmpty() || maxHops == 0) {
            List<WikiPageContext> kept = roots.stream().map(this::withoutLinks).toList();
            return new Result(kept, Map.of(), 0, 0, 0, 0,
                    maxHops, maxExpandedNodes, maxEdges, maxCharacters);
        }
        List<WikiPersonalizedPageRank.Seed> seeds = new ArrayList<>();
        for (WikiPageContext context : roots) {
            KnowledgePageHit page = context.page();
            seeds.add(new WikiPersonalizedPageRank.Seed(page.itemId(), page.title(), page.score()));
        }
        WikiPersonalizedPageRank.Exploration exploration = WikiPersonalizedPageRank.expand(
                seeds, maxHops, adjacency);
        List<WikiPersonalizedPageRank.Expanded> ranked = new ArrayList<>(exploration.expanded());
        ranked.sort(Comparator.comparingDouble(WikiPersonalizedPageRank.Expanded::adjustedScore)
                .reversed()
                .thenComparing(WikiPersonalizedPageRank.Expanded::itemId));
        List<WikiPageContext> selected = new ArrayList<>(roots);
        Map<String, List<String>> paths = new LinkedHashMap<>();
        int hopsUsed = 0;
        int edgeCount = 0;
        int characterCount = 0;
        int cursor = 0;
        while (cursor < ranked.size() && paths.size() < maxExpandedNodes) {
            int end = Math.min(ranked.size(), cursor + PAGE_LOAD_BATCH);
            Map<String, WikiPageContext> loaded = loadPages(pages, ranked.subList(cursor, end));
            for (int index = cursor; index < end && paths.size() < maxExpandedNodes; index++) {
                WikiPersonalizedPageRank.Expanded candidate = ranked.get(index);
                WikiPageContext page = loaded.get(candidate.itemId());
                if (page == null || candidate.hops() <= 0 || candidate.pathTitles().size() < 2) {
                    continue;
                }
                int characters = excerptLength(page);
                if (edgeCount + candidate.hops() > maxEdges || characterCount + characters > maxCharacters) {
                    continue;
                }
                int score = (int) Math.round(candidate.adjustedScore() * 100.0);
                if (score < 0) {
                    score = 0;
                }
                KnowledgePageHit hit = page.page();
                selected.add(new WikiPageContext(
                        new KnowledgePageHit(
                                hit.itemId(), hit.versionId(), hit.versionNo(), hit.title(),
                                hit.content(), hit.summary(), score),
                        page.outgoingLinks(),
                        page.backlinks(),
                        page.citations()));
                paths.put(candidate.itemId(), candidate.pathTitles());
                edgeCount += candidate.hops();
                characterCount += characters;
                hopsUsed = Math.max(hopsUsed, candidate.hops());
            }
            cursor = end;
        }
        return new Result(
                selected,
                paths,
                hopsUsed,
                paths.size(),
                edgeCount,
                characterCount,
                maxHops,
                maxExpandedNodes,
                maxEdges,
                maxCharacters
        );
    }

    private Map<String, WikiPageContext> loadPages(
            PageSource pages,
            List<WikiPersonalizedPageRank.Expanded> batch
    ) {
        if (pages == null || batch.isEmpty()) {
            return Map.of();
        }
        List<String> ids = new ArrayList<>();
        for (WikiPersonalizedPageRank.Expanded candidate : batch) {
            ids.add(candidate.itemId());
        }
        List<WikiPageContext> loaded = pages.load(ids);
        Map<String, WikiPageContext> byId = new HashMap<>();
        if (loaded == null) {
            return byId;
        }
        for (WikiPageContext context : loaded) {
            if (context != null && context.page() != null) {
                byId.put(context.page().itemId(), context);
            }
        }
        return byId;
    }

    private WikiPageContext withoutLinks(WikiPageContext context) {
        return new WikiPageContext(context.page(), List.of(), List.of(), context.citations());
    }

    private int excerptLength(WikiPageContext context) {
        KnowledgePageHit page = context.page();
        String summary = page.summary();
        String excerpt = summary == null || summary.isBlank() ? page.content() : summary;
        return excerpt == null ? 0 : excerpt.length();
    }

    @FunctionalInterface
    public interface PageSource {
        List<WikiPageContext> load(List<String> itemIds);
    }

    public record Result(
            List<WikiPageContext> contexts,
            Map<String, List<String>> graphPaths,
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
            graphPaths = graphPaths == null ? Map.of() : Map.copyOf(graphPaths);
        }
    }
}
