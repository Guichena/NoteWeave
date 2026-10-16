package com.noteweave.memory;

import com.noteweave.common.text.LexicalTerms;
import com.noteweave.retrieval.lexical.Bm25fScorer;
import com.noteweave.retrieval.provider.EmbeddingClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按需记忆的相关度：向量余弦与 BM25 分别排名后做 RRF，再归一化到 0 到 1。 */
final class MemoryRelevanceScorer {

    private final Bm25fScorer bm25fScorer = new Bm25fScorer();

    Result score(String query, List<Item> items, EmbeddingClient embeddingClient) {
        if (items == null || items.isEmpty()) {
            return new Result(Map.of(), List.of());
        }
        if (query == null || query.isBlank()) {
            return new Result(zeros(items), List.of("MEMORY_RELEVANCE_QUERY_ABSENT"));
        }
        List<String> reasons = new ArrayList<>();
        Map<String, Double> keyword = keywordContributions(query, items);
        Map<String, Double> vector = new LinkedHashMap<>();
        boolean vectorChannel = false;
        if (embeddingClient == null || !embeddingClient.isEnabled()) {
            reasons.add("MEMORY_RELEVANCE_VECTOR_UNAVAILABLE");
        } else {
            try {
                List<Float> queryVector = embeddingClient.embedQuery(query).singleVector();
                List<Ranked> ranked = new ArrayList<>();
                boolean anyVector = false;
                for (Item item : items) {
                    if (item.vector() == null || item.vector().isEmpty()) {
                        continue;
                    }
                    anyVector = true;
                    double cosine = cosine(queryVector, item.vector());
                    if (cosine > 0) {
                        ranked.add(new Ranked(item.id(), cosine));
                    }
                }
                if (!anyVector) {
                    reasons.add("MEMORY_RELEVANCE_VECTOR_UNAVAILABLE");
                } else {
                    vectorChannel = true;
                    ranked.sort(Comparator.comparingDouble(Ranked::score).reversed()
                            .thenComparing(Ranked::id));
                    for (int index = 0; index < ranked.size(); index++) {
                        vector.put(ranked.get(index).id(), contribution(index + 1));
                    }
                }
            } catch (RuntimeException ex) {
                reasons.add("MEMORY_RELEVANCE_VECTOR_UNAVAILABLE");
            }
        }
        boolean keywordChannel = !keyword.isEmpty();
        double maximum = (vectorChannel ? contribution(1) : 0) + (keywordChannel ? contribution(1) : 0);
        Map<String, Double> relevance = new LinkedHashMap<>();
        for (Item item : items) {
            double fused = keyword.getOrDefault(item.id(), 0d) + vector.getOrDefault(item.id(), 0d);
            double normalized = maximum <= 0 ? 0 : Math.min(1, fused / maximum);
            relevance.put(item.id(), normalized);
        }
        return new Result(relevance, List.copyOf(reasons));
    }

    private Map<String, Double> keywordContributions(String query, List<Item> items) {
        Set<String> terms = new LinkedHashSet<>(LexicalTerms.tokens(query));
        List<Bm25fScorer.Document> documents = items.stream()
                .map(item -> new Bm25fScorer.Document(item.id(), Map.of("text", item.text() == null ? "" : item.text())))
                .toList();
        List<Bm25fScorer.Hit> hits = new ArrayList<>(bm25fScorer.score(
                Bm25fScorer.DEFAULT_K1,
                List.of(new Bm25fScorer.FieldSpec("text", 1, Bm25fScorer.DEFAULT_B)),
                documents,
                terms,
                Map.of(),
                Map.of()));
        hits.sort(Comparator.comparingDouble(Bm25fScorer.Hit::score).reversed()
                .thenComparing(Bm25fScorer.Hit::id));
        Map<String, Double> contributions = new LinkedHashMap<>();
        for (int index = 0; index < hits.size(); index++) {
            contributions.put(hits.get(index).id(), contribution(index + 1));
        }
        return contributions;
    }

    private double contribution(int rank) {
        return 1d / (MemoryCompilerPolicy.RRF_K + rank);
    }

    private Map<String, Double> zeros(List<Item> items) {
        Map<String, Double> relevance = new LinkedHashMap<>();
        for (Item item : items) {
            relevance.put(item.id(), 0d);
        }
        return relevance;
    }

    private double cosine(List<Float> left, List<Float> right) {
        int length = Math.min(left.size(), right.size());
        if (length == 0 || left.size() != right.size()) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < length; index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm <= 0 || rightNorm <= 0) {
            return 0;
        }
        return Math.max(0, dot / Math.sqrt(leftNorm * rightNorm));
    }

    record Item(String id, String text, List<Float> vector) {
    }

    record Result(Map<String, Double> relevance, List<String> degradationReasons) {
        Result {
            relevance = relevance == null ? Map.of() : Map.copyOf(relevance);
            degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
        }
    }

    private record Ranked(String id, double score) {
    }
}
