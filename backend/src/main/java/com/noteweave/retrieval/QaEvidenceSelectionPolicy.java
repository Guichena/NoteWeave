package com.noteweave.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * Stable QA evidence selection shared by the online retriever and offline shadow capture.
 * The first pass keeps one item per source; the second pass fills the remaining budget in
 * candidate order without returning the same evidence identity twice.
 */
public final class QaEvidenceSelectionPolicy {
    public static final String POLICY_VERSION = "qa-source-diverse-budget-v2";
    public static final int DEFAULT_EVIDENCE_LIMIT = 6;
    public static final int DEFAULT_BUNDLE_EVIDENCE_LIMIT = DEFAULT_EVIDENCE_LIMIT;
    public static final int DEFAULT_BUNDLE_CHARACTER_LIMIT = 8_000;

    /**
     * V3：按 token 预算装箱，用 MMR 兼顾相关度与多样性。
     * 条数上限只作兜底，实际条数由预算决定；字符上限是防止估算偏差的硬性保护，正常情况下不会触发。
     */
    public static final String POLICY_VERSION_V3 = "qa-mmr-token-budget-v3";
    /** 平均每条约 350 token，8000 token 约可放 20 条；上限取 20，让预算决定大多数情况下的条数。 */
    public static final int V3_MAX_EVIDENCE = 20;
    public static final int V3_EVIDENCE_TOKEN_BUDGET = 8_000;
    public static final int V3_BUNDLE_CHARACTER_CEILING = 32_000;
    /** MMR 中相关度的权重，其余部分用于惩罚与已选证据的重复。 */
    public static final double MMR_LAMBDA = 0.7d;
    /** 相似度里文本重合与同一资料各占的比重。 */
    private static final double TEXT_SIMILARITY_WEIGHT = 0.8d;
    private static final double SAME_SOURCE_WEIGHT = 0.2d;

    private QaEvidenceSelectionPolicy() {
    }

    public static <T> List<Selection<T>> select(
            List<T> candidates,
            int limit,
            Function<T, String> sourceId,
            Function<T, String> evidenceId
    ) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        int boundedLimit = Math.min(limit, candidates.size());
        List<Selection<T>> selected = new ArrayList<>(boundedLimit);
        Set<String> seenSources = new LinkedHashSet<>();
        Set<String> seenEvidence = new LinkedHashSet<>();

        for (int index = 0; index < candidates.size() && selected.size() < boundedLimit; index++) {
            T candidate = candidates.get(index);
            String identity = identity(evidenceId.apply(candidate), index);
            if (seenEvidence.contains(identity)) {
                continue;
            }
            if (seenSources.add(text(sourceId.apply(candidate)))) {
                seenEvidence.add(identity);
                selected.add(new Selection<>(candidate, true));
            }
        }
        for (int index = 0; index < candidates.size() && selected.size() < boundedLimit; index++) {
            T candidate = candidates.get(index);
            String identity = identity(evidenceId.apply(candidate), index);
            if (seenEvidence.add(identity)) {
                selected.add(new Selection<>(candidate, false));
            }
        }
        return List.copyOf(selected);
    }

    /** Mirrors the QA retriever selection followed by the unified bundle score/character budget. */
    public static <T> List<T> selectFinalBundle(
            List<T> candidates,
            Function<T, String> sourceId,
            Function<T, String> evidenceId,
            ToDoubleFunction<T> fusedScore,
            ToIntFunction<T> characterCost
    ) {
        List<T> ranked = select(
                        candidates, DEFAULT_EVIDENCE_LIMIT, sourceId, evidenceId)
                .stream()
                .map(Selection::item)
                .sorted(Comparator.comparingDouble(fusedScore).reversed())
                .toList();
        List<T> selected = new ArrayList<>();
        long selectedCharacters = 0;
        for (T candidate : ranked) {
            if (selected.size() >= DEFAULT_BUNDLE_EVIDENCE_LIMIT) {
                break;
            }
            int nextCost = Math.max(0, characterCost.applyAsInt(candidate));
            if (selectedCharacters + nextCost > DEFAULT_BUNDLE_CHARACTER_LIMIT) {
                continue;
            }
            selected.add(candidate);
            selectedCharacters += nextCost;
        }
        return List.copyOf(selected);
    }

    /**
     * MMR 贪心装箱：每一步从剩余候选里挑 MMR 分最高、且放得进剩余 token 预算的一条，
     * 直到没有候选放得下或达到条数上限。放不下的长段落会被跳过，预算留给后面较短的候选。
     *
     * <p>相关度取各候选分数除以最高分；分数全为 0 时退回按排名递减。相似度用字符二元组的
     * Jaccard 系数加上同一资料的加成，中英文都适用，不依赖向量。</p>
     *
     * @return 按挑选顺序排列的候选
     */
    public static <T> List<T> selectMmrWithinBudget(
            List<T> candidates,
            Function<T, String> sourceId,
            Function<T, String> evidenceId,
            Function<T, String> text,
            ToDoubleFunction<T> score,
            ToIntFunction<T> tokenCost,
            int tokenBudget,
            int maxEvidence
    ) {
        if (candidates == null || candidates.isEmpty() || tokenBudget <= 0 || maxEvidence <= 0) {
            return List.of();
        }
        List<T> pool = new ArrayList<>();
        Set<String> seenEvidence = new LinkedHashSet<>();
        for (int index = 0; index < candidates.size(); index++) {
            T candidate = candidates.get(index);
            if (candidate != null && seenEvidence.add(identity(evidenceId.apply(candidate), index))) {
                pool.add(candidate);
            }
        }
        int size = pool.size();
        double[] relevance = new double[size];
        double maxScore = 0;
        for (int index = 0; index < size; index++) {
            maxScore = Math.max(maxScore, score.applyAsDouble(pool.get(index)));
        }
        for (int index = 0; index < size; index++) {
            relevance[index] = maxScore > 0
                    ? Math.max(0, score.applyAsDouble(pool.get(index))) / maxScore
                    : 1.0d / (index + 1);
        }
        List<Set<String>> shingles = new ArrayList<>(size);
        int[] costs = new int[size];
        for (int index = 0; index < size; index++) {
            shingles.add(bigrams(text.apply(pool.get(index))));
            costs[index] = Math.max(1, tokenCost.applyAsInt(pool.get(index)));
        }
        boolean[] taken = new boolean[size];
        double[] maxSimilarity = new double[size];
        List<T> selected = new ArrayList<>();
        int remaining = tokenBudget;
        while (selected.size() < maxEvidence) {
            int best = -1;
            double bestValue = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < size; index++) {
                if (taken[index] || costs[index] > remaining) {
                    continue;
                }
                double value = MMR_LAMBDA * relevance[index] - (1 - MMR_LAMBDA) * maxSimilarity[index];
                if (value > bestValue) {
                    bestValue = value;
                    best = index;
                }
            }
            if (best < 0) {
                break;
            }
            taken[best] = true;
            remaining -= costs[best];
            T chosen = pool.get(best);
            selected.add(chosen);
            String chosenSource = text(sourceId.apply(chosen));
            for (int index = 0; index < size; index++) {
                if (taken[index]) continue;
                double similarity = TEXT_SIMILARITY_WEIGHT * jaccard(shingles.get(index), shingles.get(best))
                        + (chosenSource.equals(text(sourceId.apply(pool.get(index)))) ? SAME_SOURCE_WEIGHT : 0);
                maxSimilarity[index] = Math.max(maxSimilarity[index], similarity);
            }
        }
        return List.copyOf(selected);
    }

    static Set<String> bigrams(String value) {
        Set<String> result = new java.util.HashSet<>();
        if (value == null) {
            return result;
        }
        StringBuilder normalized = new StringBuilder(value.length());
        value.codePoints()
                .filter(Character::isLetterOrDigit)
                .map(Character::toLowerCase)
                .forEach(normalized::appendCodePoint);
        int[] points = normalized.codePoints().toArray();
        for (int index = 0; index + 1 < points.length; index++) {
            result.add(new String(points, index, 2));
        }
        return result;
    }

    static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = smaller == left ? right : left;
        int intersection = 0;
        for (String item : smaller) {
            if (larger.contains(item)) intersection++;
        }
        return (double) intersection / (left.size() + right.size() - intersection);
    }

    private static String identity(String value, int index) {
        String identity = text(value);
        return identity.isBlank() ? "candidate-index:" + index : identity;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    public record Selection<T>(T item, boolean sourceDiversity) {
    }
}
