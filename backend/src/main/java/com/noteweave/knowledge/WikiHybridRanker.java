package com.noteweave.knowledge;

import com.noteweave.retrieval.provider.RerankClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Wiki 词项召回与向量召回的 RRF，以及权威度加成。 */
final class WikiHybridRanker {
    static final int RRF_K = 60;
    static final int CHANNEL_LIMIT = 40;
    static final int RERANK_LIMIT = 20;
    static final String INDEX_UNAVAILABLE = "WIKI_INDEX_UNAVAILABLE";
    static final String EMBEDDING_UNAVAILABLE = "WIKI_EMBEDDING_UNAVAILABLE";
    static final String RERANK_UNAVAILABLE = "WIKI_RERANK_UNAVAILABLE";

    private WikiHybridRanker() {
    }

    record ChannelHit(
            String itemId,
            int outgoingCount,
            int backlinkCount,
            int citationCount,
            int unresolvedCount
    ) {
    }

    record Ranked(
            String itemId,
            double score,
            int outgoingCount,
            int backlinkCount,
            int citationCount,
            int unresolvedCount
    ) {
    }

    static List<Ranked> fuse(List<ChannelHit> lexical, List<ChannelHit> vector) {
        Map<String, Accumulator> byId = new LinkedHashMap<>();
        add(byId, lexical);
        add(byId, vector);
        List<Ranked> ranked = new ArrayList<>();
        for (Accumulator accumulator : byId.values()) {
            double authority = Math.min(
                    KnowledgeWikiSearchEngine.AUTHORITY_CAP,
                    KnowledgeWikiSearchEngine.AUTHORITY_WEIGHT * Math.log1p(
                            accumulator.citationCount + accumulator.backlinkCount));
            ranked.add(new Ranked(
                    accumulator.itemId,
                    accumulator.rrf * (1.0d + authority),
                    accumulator.outgoingCount,
                    accumulator.backlinkCount,
                    accumulator.citationCount,
                    accumulator.unresolvedCount));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed()
                .thenComparing(Comparator.comparingInt(Ranked::citationCount).reversed())
                .thenComparing(Comparator.comparingInt(Ranked::backlinkCount).reversed())
                .thenComparing(Ranked::itemId));
        if (ranked.isEmpty()) {
            return List.of();
        }
        double top = ranked.get(0).score();
        return ranked.stream()
                .filter(row -> row.score() >= top * KnowledgeWikiSearchEngine.RELATIVE_CUTOFF)
                .toList();
    }

    /**
     * 按传入顺序重排窗口。命中不合法时返回 null，调用方保留融合顺序。
     * 窗口里没被返回的页面附在窗口末尾，窗口外保持原序。
     */
    static List<Ranked> rerank(List<Ranked> fused, List<RerankClient.Hit> hits) {
        if (fused == null || fused.isEmpty()) {
            return List.of();
        }
        int window = Math.min(RERANK_LIMIT, fused.size());
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        Set<Integer> seen = new HashSet<>();
        for (RerankClient.Hit hit : hits) {
            if (hit == null || hit.documentIndex() < 0 || hit.documentIndex() >= window
                    || !Double.isFinite(hit.score()) || !seen.add(hit.documentIndex())) {
                return null;
            }
        }
        List<Ranked> reordered = new ArrayList<>();
        for (RerankClient.Hit hit : hits) {
            Ranked original = fused.get(hit.documentIndex());
            reordered.add(new Ranked(
                    original.itemId(),
                    hit.score(),
                    original.outgoingCount(),
                    original.backlinkCount(),
                    original.citationCount(),
                    original.unresolvedCount()));
        }
        for (int index = 0; index < window; index++) {
            if (!seen.contains(index)) {
                reordered.add(fused.get(index));
            }
        }
        if (fused.size() > window) {
            reordered.addAll(fused.subList(window, fused.size()));
        }
        return reordered;
    }

    private static void add(Map<String, Accumulator> byId, List<ChannelHit> hits) {
        if (hits == null) {
            return;
        }
        for (int index = 0; index < hits.size(); index++) {
            ChannelHit hit = hits.get(index);
            if (hit == null || hit.itemId() == null || hit.itemId().isBlank()) {
                continue;
            }
            Accumulator accumulator = byId.computeIfAbsent(hit.itemId(), Accumulator::new);
            accumulator.rrf += 1.0d / (RRF_K + index + 1);
            accumulator.outgoingCount = Math.max(accumulator.outgoingCount, hit.outgoingCount());
            accumulator.backlinkCount = Math.max(accumulator.backlinkCount, hit.backlinkCount());
            accumulator.citationCount = Math.max(accumulator.citationCount, hit.citationCount());
            accumulator.unresolvedCount = Math.max(accumulator.unresolvedCount, hit.unresolvedCount());
        }
    }

    private static final class Accumulator {
        private final String itemId;
        private double rrf;
        private int outgoingCount;
        private int backlinkCount;
        private int citationCount;
        private int unresolvedCount;

        private Accumulator(String itemId) {
            this.itemId = itemId;
        }
    }
}
