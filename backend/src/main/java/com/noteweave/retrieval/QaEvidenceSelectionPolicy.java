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
