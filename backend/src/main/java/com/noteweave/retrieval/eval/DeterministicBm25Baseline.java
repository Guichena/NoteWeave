package com.noteweave.retrieval.eval;

import com.noteweave.retrieval.eval.RetrievalGoldSet.Candidate;
import com.noteweave.retrieval.eval.RetrievalGoldSet.GoldCase;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DeterministicBm25Baseline {
    private static final double K1 = 1.2d;
    private static final double B = 0.75d;
    private static final double CONTENT_BOOST = 3.0d;
    private static final double TITLE_BOOST = 2.0d;
    private static final double SOURCE_TYPE_BOOST = 1.0d;
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "[\\p{IsHan}]+|[\\p{L}\\p{N}][\\p{L}\\p{N}+_./-]*");

    public List<RankedEvidence> rank(GoldCase goldCase) {
        Set<String> allowedSources = new LinkedHashSet<>(goldCase.allowedSourceIds());
        List<Candidate> candidates = goldCase.candidates().stream()
                .filter(candidate -> allowedSources.isEmpty() || allowedSources.contains(candidate.sourceId()))
                .toList();
        List<String> queryTerms = new ArrayList<>(new LinkedHashSet<>(tokens(goldCase.query())));
        if (candidates.isEmpty() || queryTerms.isEmpty()) {
            return List.of();
        }

        List<TokenizedCandidate> documents = candidates.stream()
                .map(candidate -> new TokenizedCandidate(
                        candidate,
                        tokens(candidate.content()),
                        tokens(candidate.title()),
                        tokens(candidate.sourceType())))
                .toList();
        FieldStats contentStats = fieldStats(documents.stream().map(TokenizedCandidate::contentTokens).toList());
        FieldStats titleStats = fieldStats(documents.stream().map(TokenizedCandidate::titleTokens).toList());
        FieldStats typeStats = fieldStats(documents.stream().map(TokenizedCandidate::sourceTypeTokens).toList());

        return documents.stream()
                .map(document -> new RankedEvidence(
                        document.candidate().evidenceId(),
                        document.candidate().sourceId(),
                        score(document.contentTokens(), queryTerms, contentStats) * CONTENT_BOOST
                                + score(document.titleTokens(), queryTerms, titleStats) * TITLE_BOOST
                                + score(document.sourceTypeTokens(), queryTerms, typeStats) * SOURCE_TYPE_BOOST,
                        document.candidate().citationIds()))
                .filter(result -> result.score() > 0.0d)
                .sorted(Comparator.comparingDouble(RankedEvidence::score).reversed()
                        .thenComparing(RankedEvidence::evidenceId))
                .toList();
    }

    private double score(List<String> documentTokens, List<String> queryTerms, FieldStats stats) {
        if (documentTokens.isEmpty() || stats.documentCount() == 0) {
            return 0.0d;
        }
        Map<String, Integer> frequencies = frequencies(documentTokens);
        double score = 0.0d;
        for (String term : queryTerms) {
            int frequency = frequencies.getOrDefault(term, 0);
            if (frequency == 0) {
                continue;
            }
            int documentFrequency = stats.documentFrequency().getOrDefault(term, 0);
            double idf = Math.log(1.0d + (
                    stats.documentCount() - documentFrequency + 0.5d) / (documentFrequency + 0.5d));
            double lengthNormalization = K1 * (
                    1.0d - B + B * documentTokens.size() / Math.max(1.0d, stats.averageLength()));
            score += idf * frequency * (K1 + 1.0d) / (frequency + lengthNormalization);
        }
        return score;
    }

    private FieldStats fieldStats(List<List<String>> documents) {
        Map<String, Integer> documentFrequency = new HashMap<>();
        int totalLength = 0;
        for (List<String> document : documents) {
            totalLength += document.size();
            new LinkedHashSet<>(document).forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
        }
        double averageLength = documents.isEmpty() ? 0.0d : (double) totalLength / documents.size();
        return new FieldStats(documents.size(), averageLength, Map.copyOf(documentFrequency));
    }

    private Map<String, Integer> frequencies(List<String> tokens) {
        Map<String, Integer> frequencies = new HashMap<>();
        tokens.forEach(token -> frequencies.merge(token, 1, Integer::sum));
        return frequencies;
    }

    List<String> tokens(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.codePoints().allMatch(DeterministicBm25Baseline::isHan)) {
                addHanTokens(result, token);
            } else {
                result.add(token);
            }
        }
        return result;
    }

    private void addHanTokens(List<String> target, String token) {
        int[] codePoints = token.codePoints().toArray();
        if (codePoints.length == 0) {
            return;
        }
        target.add(token);
        for (int codePoint : codePoints) {
            target.add(new String(Character.toChars(codePoint)));
        }
        for (int index = 0; index + 1 < codePoints.length; index++) {
            target.add(new String(codePoints, index, 2));
        }
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    public record RankedEvidence(
            String evidenceId,
            String sourceId,
            double score,
            List<String> citationIds
    ) {
        public RankedEvidence {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }
    }

    private record TokenizedCandidate(
            Candidate candidate,
            List<String> contentTokens,
            List<String> titleTokens,
            List<String> sourceTypeTokens
    ) {
    }

    private record FieldStats(
            int documentCount,
            double averageLength,
            Map<String, Integer> documentFrequency
    ) {
    }
}
