package com.noteweave.memory;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MemoryStatementMatcher {

    private final MemoryCandidatePolicy policy;

    public MemoryStatementMatcher(MemoryCandidatePolicy policy) {
        this.policy = policy;
    }

    public boolean equivalent(String left, String right) {
        return similarity(left, right) >= policy.equivalentStatementSimilarity();
    }

    public double similarity(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) {
            return 0.0;
        }
        if (normalizedLeft.equals(normalizedRight)) {
            return 1.0;
        }
        return Math.max(
                jaccard(tokens(normalizedLeft), tokens(normalizedRight)),
                jaccard(bigrams(compact(normalizedLeft)), bigrams(compact(normalizedRight))));
    }

    String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase()
                .replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("[\\p{P}\\p{S}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : value.split("\\s+")) {
            if (!token.isBlank()) {
                result.add(token);
            }
        }
        return result;
    }

    private Set<String> bigrams(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value.length() < 2) {
            if (!value.isBlank()) {
                result.add(value);
            }
            return result;
        }
        for (int index = 0; index < value.length() - 1; index++) {
            result.add(value.substring(index, index + 2));
        }
        return result;
    }

    private String compact(String value) {
        return value.replace(" ", "");
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
}
