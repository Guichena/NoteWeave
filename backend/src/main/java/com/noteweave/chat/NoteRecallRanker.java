package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.common.text.LexicalTerms;
import com.noteweave.retrieval.NoteRetrievalStrategyProfile;
import com.noteweave.retrieval.lexical.Bm25fScorer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

@Component
public class NoteRecallRanker {
    private static final int CANDIDATE_LIMIT = 4;
    private static final int RELATION_EXPANSION_LIMIT = 3;
    private static final int VERIFY_LIMIT = 6;

    public Map<String, MetadataRank> rankMetadata(List<CandidateSource> candidates, Set<String> rawTerms) {
        List<String> queryTerms = queryTerms(rawTerms);
        Map<String, MetadataRank> metadataRanks = new HashMap<>();
        if (candidates == null || candidates.isEmpty() || queryTerms.isEmpty()) {
            return metadataRanks;
        }
        List<Bm25fScorer.Document> documents = new ArrayList<>(candidates.size());
        for (CandidateSource source : candidates) {
            documents.add(new Bm25fScorer.Document(source.sourceId(), metadataFields(source)));
        }
        Map<String, Bm25fScorer.Hit> hits = new HashMap<>();
        for (Bm25fScorer.Hit hit : new Bm25fScorer().score(
                Bm25fScorer.DEFAULT_K1,
                NoteRetrievalStrategyProfile.metadataFields(),
                documents,
                new LinkedHashSet<>(queryTerms),
                Map.of(),
                Map.of())) {
            hits.put(hit.id(), hit);
        }
        for (CandidateSource source : candidates) {
            Bm25fScorer.Hit hit = hits.get(source.sourceId());
            if (hit == null) {
                metadataRanks.put(source.sourceId(), MetadataRank.empty());
                continue;
            }
            List<String> covered = queryTerms.stream().filter(hit.matchedTerms()::contains).toList();
            metadataRanks.put(source.sourceId(), new MetadataRank(
                    Math.max(1, (int) Math.round(hit.score() * 1_000d)),
                    hit.matchedFields(),
                    covered,
                    covered.size(),
                    queryTerms.size()));
        }
        return metadataRanks;
    }

    private List<String> queryTerms(Set<String> rawTerms) {
        Set<String> terms = new LinkedHashSet<>();
        if (rawTerms != null) {
            for (String raw : rawTerms) {
                terms.addAll(LexicalTerms.queryTerms(raw));
            }
        }
        return List.copyOf(terms);
    }

    private Map<String, String> metadataFields(CandidateSource source) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", text(source.title()));
        fields.put("summary", text(source.summary()));
        fields.put("tags", text(source.tagsJson()));
        fields.put("metadata", text(source.metadataJson()));
        fields.put("sample_text", text(source.sampleText()));
        fields.put("source_type", text(source.sourceType()));
        return fields;
    }

    private String text(String value) {
        return value == null ? "" : value;
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
        return result;
    }

    private String type(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? "UNKNOWN" : normalized;
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

    public record ScoredCandidate(
            CandidateSource source,
            int metadataScore,
            int semanticScore,
            int noteScore,
            int relationScore,
            int readinessScore,
            double rerankScore
    ) {
        public ScoredCandidate(CandidateSource source, int metadataScore, int noteScore,
                               int relationScore, int readinessScore) {
            this(source, metadataScore, 0, noteScore, relationScore, readinessScore, source.score());
        }

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
            return new ScoredCandidate(source.withSelectionReason(reason), metadataScore, semanticScore,
                    noteScore, relationScore, readinessScore, rerankScore);
        }

        public ScoredCandidate withRerankScore(double score) {
            int normalized = Math.max(source.score(), (int) Math.round(score * 1000));
            return new ScoredCandidate(source.withScore(normalized), metadataScore, semanticScore,
                    noteScore, relationScore, readinessScore, score);
        }
    }

    public record Selection(
            List<ScoredCandidate> candidates,
            List<ScoredCandidate> expansions,
            List<CandidateSource> verifySources
    ) {
    }
}
