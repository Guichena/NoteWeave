package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class NoteRecallRanker {
    private static final int CANDIDATE_LIMIT = 4;
    private static final int RELATION_EXPANSION_LIMIT = 3;
    private static final int VERIFY_LIMIT = 6;

    public Map<String, MetadataRank> rankMetadata(List<CandidateSource> candidates, Set<String> rawTerms) {
        List<String> rankedTerms = rankTerms(rawTerms);
        Map<String, MetadataRank> metadataRanks = new HashMap<>();
        for (CandidateSource source : candidates) {
            metadataRanks.put(source.sourceId(), metadataRank(source, rawTerms, rankedTerms));
        }
        return metadataRanks;
    }

    public Selection select(List<ScoredCandidate> scored) {
        List<ScoredCandidate> candidates = selectCandidates(scored, CANDIDATE_LIMIT);
        Set<String> selectedIds = new LinkedHashSet<>(candidates.stream().map(ScoredCandidate::sourceId).toList());
        List<ScoredCandidate> expansions = scored.stream()
                .filter(source -> !selectedIds.contains(source.sourceId()) && source.relationScore() > 0)
                .sorted(Comparator.comparingInt(ScoredCandidate::relationScore).reversed()
                        .thenComparing(Comparator.comparingInt(ScoredCandidate::score).reversed()))
                .map(source -> source.withSelectionReason("relation-expansion"))
                .limit(RELATION_EXPANSION_LIMIT)
                .toList();
        return new Selection(candidates, expansions, verify(candidates, expansions, VERIFY_LIMIT));
    }

    private List<ScoredCandidate> selectCandidates(List<ScoredCandidate> scored, int limit) {
        List<ScoredCandidate> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        take(selected, seen, scored, limit, s -> s.noteScore() > 0, 1, "journal-quota");
        take(selected, seen, scored, limit, s -> s.metadataScore() > 0, 2, "metadata-quota");
        take(selected, seen, scored, limit, s -> s.relationScore() > 0, 1, "relation-quota");
        takeTypes(selected, seen, scored, limit, 2);
        for (ScoredCandidate source : scored) {
            if (selected.size() >= limit) {
                break;
            }
            if (seen.add(source.sourceId())) {
                selected.add(source.withSelectionReason("top-score-backfill"));
            }
        }
        return selected;
    }

    private void take(List<ScoredCandidate> selected, Set<String> seen, List<ScoredCandidate> scored,
                      int limit, Predicate<ScoredCandidate> predicate, int quota, String reason) {
        int taken = 0;
        for (ScoredCandidate source : scored) {
            if (selected.size() >= limit || taken >= quota) {
                return;
            }
            if (seen.contains(source.sourceId()) || !predicate.test(source)) {
                continue;
            }
            seen.add(source.sourceId());
            selected.add(source.withSelectionReason(reason));
            taken++;
        }
    }

    private void takeTypes(List<ScoredCandidate> selected, Set<String> seen, List<ScoredCandidate> scored,
                           int limit, int quota) {
        int taken = 0;
        Set<String> types = new LinkedHashSet<>();
        selected.forEach(source -> types.add(type(source.source().sourceType())));
        for (ScoredCandidate source : scored) {
            if (selected.size() >= limit || taken >= quota) {
                return;
            }
            String type = type(source.source().sourceType());
            if (seen.contains(source.sourceId()) || types.contains(type)) {
                continue;
            }
            seen.add(source.sourceId());
            types.add(type);
            selected.add(source.withSelectionReason("source-type-quota"));
            taken++;
        }
    }

    private List<CandidateSource> verify(List<ScoredCandidate> candidates, List<ScoredCandidate> expansions, int limit) {
        List<CandidateSource> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> types = new LinkedHashSet<>();
        for (ScoredCandidate source : candidates) {
            if (result.size() >= limit) {
                break;
            }
            if (source.source().windowCount() <= 0) {
                continue;
            }
            if (seen.add(source.sourceId())) {
                result.add(source.source().withVerifyAdmissionReason("candidate:" + source.selectionReason()));
                types.add(type(source.source().sourceType()));
            }
        }
        for (ScoredCandidate source : expansions) {
            if (result.size() >= limit) {
                break;
            }
            String type = type(source.source().sourceType());
            if (source.source().windowCount() <= 0 || seen.contains(source.sourceId()) || types.contains(type)) {
                continue;
            }
            seen.add(source.sourceId());
            types.add(type);
            result.add(source.source().withVerifyAdmissionReason("relation-expansion:source-type-quota"));
        }
        for (ScoredCandidate source : expansions) {
            if (result.size() >= limit) {
                break;
            }
            if (source.source().windowCount() > 0 && seen.add(source.sourceId())) {
                result.add(source.source().withVerifyAdmissionReason("relation-expansion"));
            }
        }
        for (ScoredCandidate source : candidates) {
            if (result.size() >= limit) {
                break;
            }
            if (seen.add(source.sourceId())) {
                result.add(source.source().withVerifyAdmissionReason(
                        "candidate:" + source.selectionReason() + ":window-fallback"));
            }
        }
        for (ScoredCandidate source : expansions) {
            if (result.size() >= limit) {
                break;
            }
            if (seen.add(source.sourceId())) {
                result.add(source.source().withVerifyAdmissionReason("relation-expansion:window-fallback"));
            }
        }
        return result;
    }

    private String type(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? "UNKNOWN" : normalized;
    }

    private List<String> rankTerms(Set<String> terms) {
        List<String> ranked = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Pattern tokenPattern = Pattern.compile("[A-Za-z0-9][A-Za-z0-9+_./-]*");
        for (String raw : terms) {
            String text = raw == null ? "" : raw.trim();
            if (text.isBlank()) {
                continue;
            }
            List<String> candidates = new ArrayList<>();
            candidates.add(text);
            Matcher matcher = tokenPattern.matcher(text);
            while (matcher.find()) {
                candidates.add(matcher.group());
            }
            for (String candidate : candidates) {
                String term = candidate.strip().replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
                if (term.isBlank()) {
                    continue;
                }
                String key = term.toLowerCase(Locale.ROOT);
                boolean hasDigit = term.chars().anyMatch(Character::isDigit);
                boolean hasUpper = term.chars().anyMatch(Character::isUpperCase);
                if (seen.contains(key) || rankStopword(key)) {
                    continue;
                }
                if (term.length() < 4 && !hasDigit && !hasUpper && !containsCjk(term)) {
                    continue;
                }
                seen.add(key);
                ranked.add(term);
            }
        }
        if (ranked.isEmpty()) {
            ranked.addAll(terms);
        }
        return ranked;
    }

    private boolean rankStopword(String value) {
        return Set.of(
                "about", "after", "and", "are", "consisting", "does", "from", "have",
                "into", "larger", "than", "that", "the", "their", "this", "with"
        ).contains(value);
    }

    private boolean containsCjk(String term) {
        for (int i = 0; i < term.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(term.charAt(i));
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return true;
            }
        }
        return false;
    }

    private MetadataRank metadataRank(CandidateSource source, Set<String> rawTerms, List<String> rankedTerms) {
        List<String> queryTerms = rankedTerms.isEmpty() ? new ArrayList<>(rawTerms) : rankedTerms;
        if (queryTerms.isEmpty()) {
            return MetadataRank.empty();
        }
        String title = safeLower(source.title());
        String summary = safeLower(source.summary());
        String tags = safeLower(source.tagsJson());
        String metadata = safeLower(source.metadataJson());
        String sample = safeLower(source.sampleText());
        String sourceType = safeLower(source.sourceType());

        double score = 0.0d;
        Set<String> covered = new LinkedHashSet<>();
        Set<String> matchedFields = new LinkedHashSet<>();
        for (String term : queryTerms) {
            String needle = term.toLowerCase(Locale.ROOT);
            double termScore = 0.0d;
            int titleHits = termHits(title, needle);
            if (titleHits > 0) {
                matchedFields.add("title");
                termScore += 22.0d * Math.min(titleHits, 3);
            }
            int summaryHits = termHits(summary, needle);
            if (summaryHits > 0) {
                matchedFields.add("summary");
                termScore += 14.0d * Math.min(summaryHits, 3);
            }
            int tagsHits = termHits(tags, needle);
            if (tagsHits > 0) {
                matchedFields.add("tags");
                termScore += 10.0d * Math.min(tagsHits, 3);
            }
            int metadataHits = termHits(metadata, needle);
            if (metadataHits > 0) {
                matchedFields.add("metadata");
                termScore += 10.0d * Math.min(metadataHits, 3);
            }
            int sampleHits = termHits(sample, needle);
            if (sampleHits > 0) {
                matchedFields.add("sample_text");
                termScore += 6.0d * Math.min(sampleHits, 3);
            }
            int typeHits = termHits(sourceType, needle);
            if (typeHits > 0) {
                matchedFields.add("source_type");
                termScore += 1.0d * Math.min(typeHits, 3);
            }
            if (termScore > 0) {
                covered.add(term);
                score += termScore * termWeight(term);
            }
        }
        score += 5.0d * covered.size() / queryTerms.size();
        return new MetadataRank(
                Math.max(0, (int) Math.round(score)),
                new ArrayList<>(matchedFields),
                new ArrayList<>(covered),
                covered.size(),
                queryTerms.size()
        );
    }

    private int termHits(String haystack, String needle) {
        if (haystack == null || haystack.isBlank() || needle == null || needle.isBlank()) {
            return 0;
        }
        int hits = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            hits++;
            index += needle.length();
        }
        return hits;
    }

    private double termWeight(String term) {
        double weight = 1.0d;
        if (term.length() >= 7) {
            weight += 0.5d;
        }
        if (term.chars().anyMatch(Character::isDigit)) {
            weight += 1.0d;
        }
        if (term.chars().anyMatch(Character::isUpperCase)) {
            weight += 0.6d;
        }
        if (term.chars().anyMatch(ch -> "/+-_.".indexOf(ch) >= 0)) {
            weight += 0.4d;
        }
        return weight;
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public record MetadataRank(
            int score,
            List<String> matchedFields,
            List<String> coverageTerms,
            int coveredQueryTerms,
            int totalQueryTerms
    ) {
        public static MetadataRank empty() {
            return new MetadataRank(0, List.of(), List.of(), 0, 0);
        }
    }

    public record ScoredCandidate(CandidateSource source, int metadataScore, int noteScore, int relationScore, int readinessScore) {
        public int score() {
            return source.score();
        }

        public String sourceId() {
            return source.sourceId();
        }

        public String selectionReason() {
            return source.selectionReason();
        }

        public ScoredCandidate withSelectionReason(String reason) {
            return new ScoredCandidate(source.withSelectionReason(reason), metadataScore, noteScore, relationScore, readinessScore);
        }
    }

    public record Selection(
            List<ScoredCandidate> candidates,
            List<ScoredCandidate> expansions,
            List<CandidateSource> verifySources
    ) {
    }
}
