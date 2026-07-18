package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.chat.NoteRetrievalService.NoteJournalHit;
import com.noteweave.chat.NoteRetrievalService.NoteRecallPlan;
import com.noteweave.chat.NoteRetrievalService.NoteRecallTrace;
import com.noteweave.chat.NoteRetrievalService.NoteRelationSignal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class NoteRecallRetriever {
    private final NoteRecallRepository repository;
    private final NoteJournalRetriever journalRetriever;
    private final NoteRelationGraph relationGraph;
    private final NoteRecallRanker ranker;

    public NoteRecallRetriever(
            NoteRecallRepository repository,
            NoteJournalRetriever journalRetriever,
            NoteRelationGraph relationGraph,
            NoteRecallRanker ranker
    ) {
        this.repository = repository;
        this.journalRetriever = journalRetriever;
        this.relationGraph = relationGraph;
        this.ranker = ranker;
    }

    public NoteRecallPlan retrieve(String workspaceId, String query) {
        Set<String> terms = extractTerms(query);
        Map<String, Integer> noteSignals = journalRetriever.sourceSignals(workspaceId, terms);
        List<NoteJournalHit> journalHits = journalRetriever.retrieve(workspaceId, query);
        Map<String, Set<String>> sourceIdsByNote = repository.sourceIdsByNote(workspaceId);
        Map<String, Set<String>> sourceIdsByAnsweredTurn = repository.sourceIdsByAnsweredTurn(workspaceId);
        List<CandidateSource> candidates = repository.findCandidates(workspaceId);
        Map<String, NoteRecallRanker.MetadataRank> metadataRanks = ranker.rankMetadata(candidates, terms);

        Set<String> anchorSourceIds = new LinkedHashSet<>();
        candidates.stream()
                .filter(source -> metadataRanks.getOrDefault(
                                source.sourceId(), NoteRecallRanker.MetadataRank.empty()).score() > 0
                        || noteSignals.getOrDefault(source.sourceId(), 0) > 0)
                .sorted(Comparator.comparingInt((CandidateSource source) ->
                        metadataRanks.getOrDefault(
                                        source.sourceId(), NoteRecallRanker.MetadataRank.empty()).score()
                                + noteSignals.getOrDefault(source.sourceId(), 0) * 5).reversed())
                .limit(8)
                .forEach(source -> anchorSourceIds.add(source.sourceId()));

        Map<String, NoteRelationSignal> relationSignals = relationSignals(
                candidates,
                anchorSourceIds,
                sourceIdsByNote,
                sourceIdsByAnsweredTurn
        );
        List<NoteRecallRanker.ScoredCandidate> scored = candidates.stream()
                .map(source -> scoreCandidate(source, metadataRanks, noteSignals, relationSignals))
                .filter(source -> source.score() > 0 || terms.isEmpty())
                .sorted(Comparator.comparingInt(NoteRecallRanker.ScoredCandidate::score).reversed())
                .toList();
        if (scored.isEmpty()) {
            List<CandidateSource> fallback = candidates.stream()
                    .limit(4)
                    .map(source -> source.withSelectionReason("workspace-recency-fallback")
                            .withVerifyAdmissionReason("candidate:workspace-recency-fallback"))
                    .toList();
            return new NoteRecallPlan(journalHits, fallback, List.of(), fallback, new NoteRecallTrace(
                    journalHits.size(), fallback.size(), 0, fallback.size(), 0, 0, 0, 0
            ));
        }

        NoteRecallRanker.Selection selection = ranker.select(scored);
        List<NoteRecallRanker.ScoredCandidate> selected = selection.candidates();
        List<NoteRecallRanker.ScoredCandidate> expansions = selection.expansions();
        List<CandidateSource> verifySources = selection.verifySources();
        return new NoteRecallPlan(
                journalHits,
                selected.stream().map(NoteRecallRanker.ScoredCandidate::source).toList(),
                expansions.stream().map(NoteRecallRanker.ScoredCandidate::source).toList(),
                verifySources,
                new NoteRecallTrace(
                        journalHits.size(),
                        selected.size(),
                        expansions.size(),
                        verifySources.size(),
                        selected.stream().mapToInt(NoteRecallRanker.ScoredCandidate::metadataScore).sum(),
                        selected.stream().mapToInt(NoteRecallRanker.ScoredCandidate::noteScore).sum(),
                        selected.stream().mapToInt(NoteRecallRanker.ScoredCandidate::relationScore).sum(),
                        selected.stream().mapToInt(NoteRecallRanker.ScoredCandidate::readinessScore).sum()
                )
        );
    }

    private NoteRecallRanker.ScoredCandidate scoreCandidate(
            CandidateSource source,
            Map<String, NoteRecallRanker.MetadataRank> metadataRanks,
            Map<String, Integer> noteSignals,
            Map<String, NoteRelationSignal> relationSignals
    ) {
        NoteRecallRanker.MetadataRank metadataRank = metadataRanks.getOrDefault(
                source.sourceId(), NoteRecallRanker.MetadataRank.empty());
        int metadataScore = metadataRank.score();
        int noteScore = noteSignals.getOrDefault(source.sourceId(), 0) * 5;
        NoteRelationSignal relationSignal = relationSignals.getOrDefault(
                source.sourceId(), NoteRelationSignal.empty(source.sourceId()));
        int relationScore = relationSignal.score();
        int readinessScore = windowReadinessScore(source);
        CandidateSource scoredSource = source.withScore(metadataScore + noteScore + relationScore + readinessScore)
                .withRecallSignals(recallSignals(source, metadataRank, noteScore, relationSignal))
                .withMetadataRank(metadataRank.matchedFields(), metadataRank.coverageTerms(),
                        metadataRank.coveredQueryTerms(), metadataRank.totalQueryTerms());
        return new NoteRecallRanker.ScoredCandidate(
                scoredSource, metadataScore, noteScore, relationScore, readinessScore);
    }

    private Map<String, NoteRelationSignal> relationSignals(
            List<CandidateSource> sources,
            Set<String> anchorSourceIds,
            Map<String, Set<String>> sourceIdsByNote,
            Map<String, Set<String>> sourceIdsByAnsweredTurn
    ) {
        if (sources.isEmpty() || anchorSourceIds.isEmpty()) {
            return Map.of();
        }
        Map<String, CandidateSource> sourcesById = new HashMap<>();
        Map<String, Set<String>> tagsBySource = new HashMap<>();
        for (CandidateSource source : sources) {
            sourcesById.put(source.sourceId(), source);
            tagsBySource.put(source.sourceId(), relationGraph.tags(source.tagsJson()));
        }
        Map<String, NoteRelationSignalAccumulator> accumulators = new HashMap<>();
        for (CandidateSource source : sources) {
            if (anchorSourceIds.contains(source.sourceId())) {
                continue;
            }
            Set<String> tags = tagsBySource.getOrDefault(source.sourceId(), Set.of());
            int tagOverlap = 0;
            for (String anchorId : anchorSourceIds) {
                Set<String> anchorTags = tagsBySource.getOrDefault(anchorId, Set.of());
                for (String tag : tags) {
                    if (anchorTags.contains(tag)) {
                        tagOverlap++;
                    }
                }
            }
            NoteRelationSignalAccumulator accumulator = accumulators.computeIfAbsent(
                    source.sourceId(), ignored -> new NoteRelationSignalAccumulator(source.sourceId()));
            accumulator.sharedTagCount += tagOverlap;
            int titleOverlap = 0;
            for (String anchorId : anchorSourceIds) {
                CandidateSource anchor = sourcesById.get(anchorId);
                if (anchor == null) {
                    continue;
                }
                titleOverlap = Math.max(titleOverlap, relationGraph.overlapTerms(
                        source.title() + "\n" + source.summary(),
                        anchor.title() + "\n" + anchor.summary()));
            }
            accumulator.titleOverlapScore = Math.max(accumulator.titleOverlapScore, titleOverlap);
        }
        Map<String, Integer> coCitation = relationGraph.coOccurrence(anchorSourceIds, sourceIdsByNote);
        coCitation.forEach((sourceId, value) -> accumulators.computeIfAbsent(
                sourceId, ignored -> new NoteRelationSignalAccumulator(sourceId)).coCitedNoteCount += value);
        Map<String, Integer> turnCoCitation = relationGraph.coOccurrence(anchorSourceIds, sourceIdsByAnsweredTurn);
        turnCoCitation.forEach((sourceId, value) -> accumulators.computeIfAbsent(
                sourceId, ignored -> new NoteRelationSignalAccumulator(sourceId)).coCitedTurnCount += value);
        Map<String, Map<String, Integer>> graph = relationGraph.build(
                sources, sourceIdsByNote, sourceIdsByAnsweredTurn);
        Map<String, Double> graphScores = relationGraph.propagate(graph, anchorSourceIds, 4, 0.2d);
        graphScores.forEach((sourceId, value) -> accumulators.computeIfAbsent(
                sourceId,
                ignored -> new NoteRelationSignalAccumulator(sourceId)
        ).graphNeighborhoodScore = Math.max(0, (int) Math.round(value * 24)));

        Map<String, NoteRelationSignal> signals = new HashMap<>();
        for (Map.Entry<String, NoteRelationSignalAccumulator> entry : accumulators.entrySet()) {
            NoteRelationSignal signal = entry.getValue().freeze();
            if (signal.score() > 0) {
                signals.put(entry.getKey(), signal);
            }
        }
        return signals;
    }

    private String recallSignals(
            CandidateSource source,
            NoteRecallRanker.MetadataRank metadataRank,
            int noteScore,
            NoteRelationSignal relationSignal
    ) {
        List<String> signals = new ArrayList<>();
        if (metadataRank.score() > 0) {
            signals.add("coverage-aware-metadata-rank");
            if (!metadataRank.matchedFields().isEmpty()) {
                signals.add("fields=" + String.join("/", metadataRank.matchedFields()));
            }
        }
        if (noteScore > 0) {
            signals.add("journal-note");
        }
        if (relationSignal.score() > 0) {
            signals.add("relation-expansion");
            if (relationSignal.sharedTagCount() > 0) {
                signals.add("tag-overlap");
            }
            if (relationSignal.coCitedNoteCount() > 0) {
                signals.add("co-cited-note");
            }
            if (relationSignal.coCitedTurnCount() > 0) {
                signals.add("turn-citation-neighbor");
            }
            if (relationSignal.titleOverlapScore() > 0) {
                signals.add("title-summary-neighbor");
            }
            if (relationSignal.graphNeighborhoodScore() > 0) {
                signals.add("graph-neighbor");
            }
        }
        if (source.windowCount() > 0) {
            signals.add("source-window-ready");
        } else {
            signals.add("source-window-missing");
        }
        if (signals.isEmpty()) {
            signals.add("workspace-recency");
        }
        return String.join(", ", signals);
    }

    private int windowReadinessScore(CandidateSource source) {
        if (source.windowCount() <= 0) {
            return -18;
        }
        return Math.min(12, 4 + source.windowCount());
    }

    private Set<String> extractTerms(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        String[] parts = normalized.split("[^\\p{IsHan}a-zA-Z0-9]+");
        Set<String> terms = new LinkedHashSet<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                terms.add(part);
            }
        }
        if (terms.isEmpty() && normalized.length() >= 2) {
            terms.add(normalized);
        }
        return terms;
    }

    private static final class NoteRelationSignalAccumulator {
        private final String sourceId;
        private int sharedTagCount;
        private int coCitedNoteCount;
        private int coCitedTurnCount;
        private int titleOverlapScore;
        private int graphNeighborhoodScore;

        private NoteRelationSignalAccumulator(String sourceId) {
            this.sourceId = sourceId;
        }

        private NoteRelationSignal freeze() {
            int score = sharedTagCount * 2
                    + coCitedNoteCount * 3
                    + coCitedTurnCount * 4
                    + titleOverlapScore
                    + graphNeighborhoodScore;
            return new NoteRelationSignal(
                    sourceId,
                    sharedTagCount,
                    coCitedNoteCount,
                    coCitedTurnCount,
                    titleOverlapScore,
                    graphNeighborhoodScore,
                    score
            );
        }
    }
}
