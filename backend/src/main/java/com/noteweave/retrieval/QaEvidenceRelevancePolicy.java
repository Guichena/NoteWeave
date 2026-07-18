package com.noteweave.retrieval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic lexical sufficiency guard shared by online QA and offline shadow evaluation.
 * It does not rerank candidates; it only rejects candidates whose text cannot support enough
 * meaningful terms from the question.
 */
public final class QaEvidenceRelevancePolicy {
    public static final String POLICY_VERSION_V1 = "qa-lexical-sufficiency-v1";
    public static final String POLICY_VERSION_V2 = "qa-lexical-sufficiency-v2";
    /** Backward-compatible default API remains on v2. */
    public static final String POLICY_VERSION = POLICY_VERSION_V2;

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]+|[a-zA-Z0-9]+");
    private static final Pattern ASCII_TOKEN_PATTERN = Pattern.compile("[a-zA-Z0-9]+");
    private static final String CURRENT_QUESTION_PREFIX = "当前问题：";
    private static final String TOPIC_ANCHOR_PREFIX = "主题锚点：";
    private static final double MINIMUM_QUERY_TERM_COVERAGE = 0.30d;
    private static final double MINIMUM_FALLBACK_COVERAGE = 0.25d;
    private static final int MINIMUM_MATCHED_TERMS = 2;
    private static final List<Set<String>> STRONG_ANCHOR_PAIRS = List.of(
            Set.of("artifact", "version"),
            Set.of("noteweave", "v2")
    );
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "with", "from", "by",
            "as", "at", "is", "are", "was", "were", "be", "been", "being", "do", "does", "did",
            "how", "what", "which", "who", "when", "where", "why", "i", "we", "you", "it", "they",
            "this", "that", "these", "those", "main", "please", "tell", "describe"
    );
    private static final Set<String> GENERIC_IDENTIFIER_TOKENS = Set.of(
            "noteweave", "researchagent"
    );
    private static final Set<String> GENERIC_QUERY_TERMS = Set.of(
            "summarize", "summary", "explain", "meaning", "continue",
            "请总", "总结", "请同", "同时", "时总", "请介", "介绍", "请解", "解释",
            "请说", "说明", "含义", "整理", "继续", "和"
    );
    private static final List<String> GENERIC_QUERY_PHRASES = List.of(
            "请同时总结", "请总结", "继续总结", "先介绍", "请介绍", "请解释",
            "的含义", "的关键要求", "这些", "的共同目标"
    );

    private QaEvidenceRelevancePolicy() {
    }

    public static boolean isRelevant(String query, String title, String content) {
        return evaluate(query, title, content).relevant();
    }

    public static Evaluation evaluate(String query, String title, String content) {
        return evaluate(POLICY_VERSION_V2, query, title, content);
    }

    public static boolean isRelevant(
            String policyVersion,
            String query,
            String title,
            String content
    ) {
        return evaluate(policyVersion, query, title, content).relevant();
    }

    public static Evaluation evaluate(
            String policyVersion,
            String query,
            String title,
            String content
    ) {
        boolean v2 = requireV2Flag(policyVersion);
        String policyQuery = v2 ? relevanceQuery(query) : text(query);
        Set<String> queryTerms = terms(policyQuery, true);
        Set<String> evidenceTerms = terms(text(title) + "\n" + text(content), false);
        int matched = 0;
        for (String term : queryTerms) {
            if (evidenceTerms.contains(term)) {
                matched++;
            }
        }
        if (queryTerms.isEmpty()) {
            return new Evaluation(false, 0, 0, 0.0d);
        }
        double coverage = matched / (double) queryTerms.size();
        int requiredMatches = queryTerms.size() <= 2 ? 1 : MINIMUM_MATCHED_TERMS;
        boolean relevant = matched >= requiredMatches
                && coverage + 1.0e-9d >= MINIMUM_QUERY_TERM_COVERAGE;
        return new Evaluation(relevant, queryTerms.size(), matched, coverage);
    }

    public static Set<Integer> admittedIndexes(String query, List<Candidate> candidates) {
        return admittedIndexes(POLICY_VERSION_V2, query, candidates);
    }

    public static Set<Integer> admittedIndexes(
            String policyVersion,
            String query,
            List<Candidate> candidates
    ) {
        boolean v2 = requireV2Flag(policyVersion);
        if (candidates == null || candidates.isEmpty()) {
            return Set.of();
        }
        String policyQuery = v2 ? relevanceQuery(query) : text(query);
        List<Evaluation> evaluations = new ArrayList<>(candidates.size());
        Set<Integer> standardAnchors = new LinkedHashSet<>();
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            Evaluation evaluation = evaluate(
                    policyVersion, query, candidate.title(), candidate.content());
            evaluations.add(evaluation);
            if (evaluation.relevant()) {
                standardAnchors.add(index);
            }
        }

        Set<Integer> admitted = new LinkedHashSet<>(standardAnchors);
        if (v2) {
            for (int index = 0; index < candidates.size(); index++) {
                if (hasSupportedDistinctiveIdentifier(query, candidates.get(index))) {
                    admitted.add(index);
                }
            }
        }
        Set<Integer> continuationAnchors = Set.copyOf(admitted);
        if (admitted.isEmpty()) {
            int fallbackIndex = bestFallbackIndex(policyQuery, candidates, evaluations);
            if (fallbackIndex >= 0) {
                admitted.add(fallbackIndex);
            }
        }

        for (int anchorIndex : continuationAnchors) {
            Candidate anchor = candidates.get(anchorIndex);
            if (anchor.chunkNo() != 0) {
                continue;
            }
            for (int index = 0; index < candidates.size(); index++) {
                Candidate candidate = candidates.get(index);
                if (candidate.chunkNo() == 1
                        && anchor.sourceId().equals(candidate.sourceId())
                        && anchor.sourceSnapshotId().equals(candidate.sourceSnapshotId())) {
                    admitted.add(index);
                }
            }
        }
        return Collections.unmodifiableSet(admitted);
    }

    private static int bestFallbackIndex(
            String query,
            List<Candidate> candidates,
            List<Evaluation> evaluations
    ) {
        int bestIndex = -1;
        for (int index = 0; index < evaluations.size(); index++) {
            Evaluation evaluation = evaluations.get(index);
            if (evaluation.matchedTermCount() < MINIMUM_MATCHED_TERMS
                    || evaluation.queryTermCoverage() + 1.0e-9d < MINIMUM_FALLBACK_COVERAGE
                    || !hasStrongAnchor(query, candidates.get(index))) {
                continue;
            }
            if (bestIndex < 0 || betterFallback(
                    candidates.get(index), evaluation,
                    candidates.get(bestIndex), evaluations.get(bestIndex))) {
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static boolean hasStrongAnchor(String query, Candidate candidate) {
        Set<String> queryTerms = terms(query, true);
        Set<String> evidenceTerms = terms(candidate.title() + "\n" + candidate.content(), false);
        return STRONG_ANCHOR_PAIRS.stream()
                .anyMatch(pair -> queryTerms.containsAll(pair) && evidenceTerms.containsAll(pair));
    }

    private static boolean hasSupportedDistinctiveIdentifier(String query, Candidate candidate) {
        Set<String> identifiers = distinctiveIdentifiers(relevanceQuery(query));
        if (identifiers.isEmpty()) {
            return false;
        }
        Set<String> evidenceAsciiTokens = asciiTokens(candidate.title() + "\n" + candidate.content());
        Set<String> matchedIdentifiers = new LinkedHashSet<>(identifiers);
        matchedIdentifiers.retainAll(evidenceAsciiTokens);
        if (matchedIdentifiers.isEmpty()) {
            return false;
        }

        String supportQuery = relevanceQuery(query);
        for (String phrase : GENERIC_QUERY_PHRASES) {
            supportQuery = supportQuery.replace(phrase, " ");
        }
        Set<String> remainingTerms = new LinkedHashSet<>(terms(supportQuery, true));
        matchedIdentifiers.stream().map(QaEvidenceRelevancePolicy::normalizeEnglish)
                .forEach(remainingTerms::remove);
        identifiers.stream().map(QaEvidenceRelevancePolicy::normalizeEnglish)
                .forEach(remainingTerms::remove);
        remainingTerms.removeAll(GENERIC_QUERY_TERMS);
        if (remainingTerms.isEmpty()) {
            return true;
        }

        Set<String> evidenceTerms = terms(candidate.title() + "\n" + candidate.content(), false);
        long supportedTerms = remainingTerms.stream().filter(evidenceTerms::contains).count();
        return supportedTerms > 0
                && supportedTerms / (double) remainingTerms.size() + 1.0e-9d
                >= MINIMUM_QUERY_TERM_COVERAGE;
    }

    private static Set<String> distinctiveIdentifiers(String query) {
        Set<String> identifiers = new LinkedHashSet<>();
        Matcher matcher = ASCII_TOKEN_PATTERN.matcher(text(query));
        while (matcher.find()) {
            String token = matcher.group();
            String normalized = token.toLowerCase(Locale.ROOT);
            if (GENERIC_IDENTIFIER_TOKENS.contains(normalized)) {
                continue;
            }
            boolean internalUppercase = token.chars().skip(1).anyMatch(Character::isUpperCase);
            boolean containsLetter = token.chars().anyMatch(Character::isLetter);
            boolean containsDigit = token.chars().anyMatch(Character::isDigit);
            if (token.length() >= 8 && (internalUppercase
                    || (containsLetter && containsDigit)
                    || token.length() >= 16)) {
                identifiers.add(normalized);
            }
        }
        return identifiers;
    }

    private static Set<String> asciiTokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = ASCII_TOKEN_PATTERN.matcher(text(value));
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    private static String relevanceQuery(String query) {
        String value = text(query);
        if (!value.contains(CURRENT_QUESTION_PREFIX)) {
            return value;
        }
        StringBuilder focused = new StringBuilder();
        for (String line : value.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(CURRENT_QUESTION_PREFIX)) {
                appendFocusedLine(focused, trimmed.substring(CURRENT_QUESTION_PREFIX.length()));
            } else if (trimmed.startsWith(TOPIC_ANCHOR_PREFIX)) {
                appendFocusedLine(focused, trimmed.substring(TOPIC_ANCHOR_PREFIX.length()));
            }
        }
        return focused.isEmpty() ? value : focused.toString();
    }

    private static void appendFocusedLine(StringBuilder focused, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!focused.isEmpty()) {
            focused.append('\n');
        }
        focused.append(value.trim());
    }

    private static boolean betterFallback(
            Candidate candidate,
            Evaluation evaluation,
            Candidate current,
            Evaluation currentEvaluation
    ) {
        if (evaluation.matchedTermCount() != currentEvaluation.matchedTermCount()) {
            return evaluation.matchedTermCount() > currentEvaluation.matchedTermCount();
        }
        int coverage = Double.compare(
                evaluation.queryTermCoverage(), currentEvaluation.queryTermCoverage());
        if (coverage != 0) {
            return coverage > 0;
        }
        return candidate.score() > current.score();
    }

    private static Set<String> terms(String value, boolean removeStopWords) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text(value).toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (isHan(token)) {
                addHanTerms(token, terms);
                continue;
            }
            String normalized = normalizeEnglish(token);
            if (normalized.isBlank() || normalized.length() < 2
                    || (removeStopWords && STOP_WORDS.contains(normalized))) {
                continue;
            }
            terms.add(normalized);
        }
        return terms;
    }

    private static void addHanTerms(String token, Set<String> terms) {
        int[] codePoints = token.codePoints().toArray();
        if (codePoints.length == 1) {
            terms.add(token);
            return;
        }
        for (int index = 0; index < codePoints.length - 1; index++) {
            terms.add(new String(codePoints, index, 2));
        }
    }

    private static boolean isHan(String token) {
        return !token.isEmpty() && Character.UnicodeScript.of(token.codePointAt(0))
                == Character.UnicodeScript.HAN;
    }

    private static String normalizeEnglish(String token) {
        if (token.startsWith("verif")) {
            return "verif";
        }
        if (token.startsWith("capabilit")) {
            return "capability";
        }
        if (token.startsWith("boundar")) {
            return "boundary";
        }
        if (token.startsWith("regenerat")) {
            return "regenerat";
        }
        if (token.startsWith("compar")) {
            return "compar";
        }
        if (token.startsWith("citat")) {
            return "citation";
        }
        if (token.startsWith("implement")) {
            return "implement";
        }
        if (token.length() > 5 && token.endsWith("ies")) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.length() > 5 && token.endsWith("ing")) {
            return token.substring(0, token.length() - 3);
        }
        if (token.length() > 4 && token.endsWith("ed")) {
            return token.substring(0, token.length() - 2);
        }
        if (token.length() > 3 && token.endsWith("s")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static boolean requireV2Flag(String policyVersion) {
        if (POLICY_VERSION_V2.equals(policyVersion)) {
            return true;
        }
        if (POLICY_VERSION_V1.equals(policyVersion)) {
            return false;
        }
        throw new IllegalArgumentException(
                "Unsupported QA relevance policy version: " + text(policyVersion));
    }

    public record Evaluation(
            boolean relevant,
            int queryTermCount,
            int matchedTermCount,
            double queryTermCoverage
    ) {
    }

    public record Candidate(
            String sourceId,
            String sourceSnapshotId,
            int chunkNo,
            String title,
            String content,
            double score
    ) {
        public Candidate {
            sourceId = text(sourceId);
            sourceSnapshotId = text(sourceSnapshotId);
            title = text(title);
            content = text(content);
        }
    }
}
