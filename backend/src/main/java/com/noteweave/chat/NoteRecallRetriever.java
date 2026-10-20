package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.chat.NoteRetrievalService.NoteJournalHit;
import com.noteweave.chat.NoteRetrievalService.NoteRecallPlan;
import com.noteweave.chat.NoteRetrievalService.NoteRecallTrace;
import com.noteweave.chat.NoteRetrievalService.NoteRelationSignal;
import com.noteweave.common.text.LexicalTerms;
import com.noteweave.retrieval.NoteRetrievalStrategyProfile;
import com.noteweave.retrieval.RetrievalChannels;
import com.noteweave.retrieval.note.NoteSourceRerankService;
import com.noteweave.retrieval.note.NoteSourceSearchPort;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@Component
public class NoteRecallRetriever {
    private final NoteRecallRepository repository;
    private final NoteJournalRetriever journalRetriever;
    private final NoteRelationGraph relationGraph;
    private final NoteRecallRanker ranker;
    private final EmbeddingClient embeddingClient;
    private final NoteSourceSearchPort sourceSearchPort;
    private final NoteSourceRerankService rerankService;
    private final NoteTagResolver tagResolver;
    private final boolean localMetadataFallbackEnabled;

    public NoteRecallRetriever(
            NoteRecallRepository repository,
            NoteJournalRetriever journalRetriever,
            NoteRelationGraph relationGraph,
            NoteRecallRanker ranker
    ) {
        this(repository, journalRetriever, relationGraph, ranker, null, null, null, null, true);
    }

    @Autowired
    public NoteRecallRetriever(
            NoteRecallRepository repository,
            NoteJournalRetriever journalRetriever,
            NoteRelationGraph relationGraph,
            NoteRecallRanker ranker,
            EmbeddingClient embeddingClient,
            NoteSourceSearchPort sourceSearchPort,
            NoteSourceRerankService rerankService,
            NoteTagResolver tagResolver,
            @Value("${noteweave.retrieval.note.local-metadata-fallback-enabled:false}")
            boolean localMetadataFallbackEnabled
    ) {
        this.repository = repository;
        this.journalRetriever = journalRetriever;
        this.relationGraph = relationGraph;
        this.ranker = ranker;
        this.embeddingClient = embeddingClient;
        this.sourceSearchPort = sourceSearchPort;
        this.rerankService = rerankService;
        this.tagResolver = tagResolver;
        this.localMetadataFallbackEnabled = localMetadataFallbackEnabled;
    }

    public NoteRecallPlan retrieve(String workspaceId, String query) {
        return retrieve(workspaceId, RetrievalChannels.same(query));
    }

    public NoteRecallPlan retrieve(String workspaceId, RetrievalChannels channels) {
        RetrievalChannels safeChannels = channels == null ? RetrievalChannels.same("") : channels;
        String lexicalQuery = safeChannels.lexicalQuery();
        List<String> degradationReasons = new ArrayList<>();
        Set<String> terms = LexicalTerms.queryTerms(lexicalQuery);
        Map<String, Integer> noteSignals = journalRetriever.sourceSignals(workspaceId, terms);
        List<NoteJournalHit> journalHits = journalRetriever.retrieve(workspaceId, lexicalQuery);
        boolean sourceRecallUnavailable = embeddingClient == null
                || sourceSearchPort == null
                || !embeddingClient.isEnabled();
        List<String> semanticOrder = new ArrayList<>();
        List<String> metadataOrder = new ArrayList<>();
        if (!sourceRecallUnavailable) {
            try {
                var vector = embeddingClient.embedQuery(safeChannels.vectorQuery()).singleVector();
                for (var hit : sourceSearchPort.semanticRetrieve(
                        workspaceId, vector, NoteRetrievalStrategyProfile.CHANNEL_LIMIT)) {
                    if (hit != null && hit.sourceId() != null && !hit.sourceId().isBlank()) {
                        semanticOrder.add(hit.sourceId());
                    }
                }
                for (var hit : metadataHits(workspaceId, safeChannels)) {
                    if (hit != null && hit.sourceId() != null && !hit.sourceId().isBlank()) {
                        metadataOrder.add(hit.sourceId());
                    }
                }
            } catch (RuntimeException ex) {
                sourceRecallUnavailable = true;
                semanticOrder.clear();
                metadataOrder.clear();
            }
        }
        if (sourceRecallUnavailable) {
            degradationReasons.add("NOTE_SOURCE_RECALL_UNAVAILABLE");
            if (!localMetadataFallbackEnabled) {
                throw new RetrievalProviderException(
                        "NOTE_SOURCE_RETRIEVAL_PROVIDER_UNAVAILABLE",
                        "Note source retrieval provider is unavailable"
                );
            }
            degradationReasons.add("NOTE_LOCAL_METADATA_FALLBACK");
            metadataOrder = new ArrayList<>(emptyIfNull(repository.findLexicalSourceIds(
                    workspaceId, terms, NoteRetrievalStrategyProfile.CHANNEL_LIMIT)));
        }
        List<String> tagOrder = tagResolver == null
                ? List.of()
                : emptyIfNull(repository.findTagSourceIds(
                        workspaceId, terms, NoteRetrievalStrategyProfile.CHANNEL_LIMIT));
        List<String> journalOrder = orderByScore(noteSignals);
        List<String> directIds = unionIds(List.of(semanticOrder, metadataOrder, tagOrder, journalOrder));
        List<CandidateSource> directSources = repository.findCurrentSourcesByIds(workspaceId, directIds);
        Set<String> loadedIds = new LinkedHashSet<>();
        directSources.forEach(source -> loadedIds.add(source.sourceId()));
        semanticOrder = present(semanticOrder, loadedIds);
        metadataOrder = present(metadataOrder, loadedIds);
        tagOrder = present(tagOrder, loadedIds);
        journalOrder = present(journalOrder, loadedIds);

        NoteTagResolver.Resolution tagResolution = tagResolver == null
                ? new NoteTagResolver.Resolution(List.of(), List.copyOf(terms), Map.of())
                : tagResolver.resolve(terms, directSources);
        Map<String, Integer> tagScores = tagResolution.sourceScores();
        tagOrder = orderByScore(tagScores);
        Map<String, NoteRecallRanker.MetadataRank> metadataRanks = sourceRecallUnavailable
                ? ranker.rankMetadata(directSources, terms)
                : Map.of();
        if (sourceRecallUnavailable) {
            metadataOrder = directSources.stream()
                    .filter(source -> metadataRanks.getOrDefault(
                            source.sourceId(), NoteRecallRanker.MetadataRank.empty()).score() > 0)
                    .sorted(Comparator.comparingInt((CandidateSource source) -> metadataRanks.getOrDefault(
                                    source.sourceId(), NoteRecallRanker.MetadataRank.empty()).score()).reversed()
                            .thenComparing(CandidateSource::sourceId))
                    .map(CandidateSource::sourceId)
                    .toList();
        }

        Map<String, Integer> preRelation = new HashMap<>();
        addChannel(preRelation, semanticOrder, NoteRetrievalStrategyProfile.VECTOR_WEIGHT);
        addChannel(preRelation, metadataOrder, NoteRetrievalStrategyProfile.METADATA_WEIGHT);
        addChannel(preRelation, tagOrder, NoteRetrievalStrategyProfile.TAG_WEIGHT);
        addChannel(preRelation, journalOrder, NoteRetrievalStrategyProfile.JOURNAL_WEIGHT);
        List<CandidateSource> anchors = directSources.stream()
                .filter(source -> preRelation.getOrDefault(source.sourceId(), 0) > 0)
                .sorted(Comparator.comparingInt((CandidateSource source) ->
                                preRelation.getOrDefault(source.sourceId(), 0)).reversed()
                        .thenComparing(CandidateSource::sourceId))
                .limit(NoteRetrievalStrategyProfile.ANCHOR_LIMIT)
                .toList();
        List<CandidateSource> neighbors = expandNeighbors(workspaceId, anchors, loadedIds);
        List<CandidateSource> candidates = new ArrayList<>(directSources);
        for (CandidateSource neighbor : neighbors) {
            if (loadedIds.add(neighbor.sourceId())) {
                candidates.add(neighbor);
            }
        }
        Set<String> anchorSourceIds = new LinkedHashSet<>();
        anchors.forEach(source -> anchorSourceIds.add(source.sourceId()));
        Map<String, Set<String>> sourceIdsByNote = anchorSourceIds.isEmpty()
                ? Map.of()
                : emptyMap(repository.sourceIdsByNoteForSources(workspaceId, loadedIds));
        Map<String, Set<String>> sourceIdsByAnsweredTurn = anchorSourceIds.isEmpty()
                ? Map.of()
                : emptyMap(repository.sourceIdsByAnsweredTurnForSources(workspaceId, loadedIds));
        Map<String, NoteRelationSignal> relationSignals = relationSignals(
                candidates, anchorSourceIds, sourceIdsByNote, sourceIdsByAnsweredTurn);
        List<String> relationOrder = relationSignals.values().stream()
                .filter(signal -> signal.score() > 0)
                .sorted(Comparator.comparingInt(NoteRelationSignal::score).reversed()
                        .thenComparing(NoteRelationSignal::sourceId))
                .map(NoteRelationSignal::sourceId)
                .toList();

        Map<String, Integer> fused = new HashMap<>();
        Map<String, Integer> semanticParts = new HashMap<>();
        Map<String, Integer> metadataParts = new HashMap<>();
        Map<String, Integer> tagParts = new HashMap<>();
        Map<String, Integer> journalParts = new HashMap<>();
        Map<String, Integer> relationParts = new HashMap<>();
        addChannel(fused, semanticParts, semanticOrder, NoteRetrievalStrategyProfile.VECTOR_WEIGHT);
        addChannel(fused, metadataParts, metadataOrder, NoteRetrievalStrategyProfile.METADATA_WEIGHT);
        addChannel(fused, tagParts, tagOrder, NoteRetrievalStrategyProfile.TAG_WEIGHT);
        addChannel(fused, journalParts, journalOrder, NoteRetrievalStrategyProfile.JOURNAL_WEIGHT);
        addChannel(fused, relationParts, relationOrder, NoteRetrievalStrategyProfile.RELATION_WEIGHT);

        List<NoteRecallRanker.ScoredCandidate> scored = candidates.stream()
                .filter(source -> fused.getOrDefault(source.sourceId(), 0) > 0)
                .map(source -> scoreCandidate(
                        source,
                        metadataRanks,
                        metadataParts.getOrDefault(source.sourceId(), 0),
                        tagParts.getOrDefault(source.sourceId(), 0),
                        semanticParts.getOrDefault(source.sourceId(), 0),
                        journalParts.getOrDefault(source.sourceId(), 0),
                        relationParts.getOrDefault(source.sourceId(), 0),
                        relationSignals.getOrDefault(source.sourceId(), NoteRelationSignal.empty(source.sourceId())),
                        fused.getOrDefault(source.sourceId(), 0)))
                .sorted(Comparator.comparingInt(NoteRecallRanker.ScoredCandidate::score).reversed()
                        .thenComparing(NoteRecallRanker.ScoredCandidate::sourceId))
                .toList();
        Map<String, Long> baseMeasurements = new LinkedHashMap<>();
        baseMeasurements.put("source_candidate_count", (long) candidates.size());
        baseMeasurements.put("resolved_tag_count", (long) tagResolution.resolvedTags().size());
        baseMeasurements.put("semantic_hit_count", (long) semanticOrder.size());
        baseMeasurements.put("metadata_index_hit_count", (long) metadataOrder.size());
        baseMeasurements.put("note_local_fallback_used", sourceRecallUnavailable ? 1L : 0L);
        if (scored.isEmpty()) {
            degradationReasons.add("note_no_relevant_candidates");
            return new NoteRecallPlan(journalHits, List.of(), List.of(), List.of(), new NoteRecallTrace(
                    journalHits.size(), 0, 0, 0, 0, 0, 0, 0
            ), true, List.copyOf(degradationReasons), baseMeasurements);
        }

        NoteSourceRerankService.Outcome rerankOutcome = rerankService == null
                ? new NoteSourceRerankService.Outcome(scored, true,
                        List.of("NOTE_SOURCE_RERANK_UNAVAILABLE"), "")
                : rerankService.rerank(safeChannels.rerankQuery(),
                        scored.stream().limit(NoteRetrievalStrategyProfile.RERANK_LIMIT).toList(), 16);
        degradationReasons.addAll(rerankOutcome.degradationReasons());
        List<NoteRecallRanker.ScoredCandidate> reranked = rerankOutcome.candidates();
        NoteRecallRanker.Selection selection = ranker.select(reranked);
        List<NoteRecallRanker.ScoredCandidate> selected = selection.candidates();
        List<NoteRecallRanker.ScoredCandidate> expansions = selection.expansions();
        List<CandidateSource> verifySources = selection.verifySources();
        baseMeasurements.put("source_rerank_count", (long) reranked.size());
        baseMeasurements.put("verify_batch_count", (long) verifySources.size());
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
                        selected.stream().mapToInt(NoteRecallRanker.ScoredCandidate::semanticScore).sum(),
                        selected.stream().mapToInt(NoteRecallRanker.ScoredCandidate::noteScore).sum(),
                        selected.stream().mapToInt(NoteRecallRanker.ScoredCandidate::relationScore).sum(),
                        selected.stream().mapToInt(NoteRecallRanker.ScoredCandidate::readinessScore).sum()
                ),
                !degradationReasons.isEmpty(),
                List.copyOf(new LinkedHashSet<>(degradationReasons)),
                baseMeasurements
        );
    }

    private NoteRecallRanker.ScoredCandidate scoreCandidate(
            CandidateSource source,
            Map<String, NoteRecallRanker.MetadataRank> metadataRanks,
            int metadataChannelScore,
            int tagChannelScore,
            int semanticScore,
            int noteScore,
            int relationScore,
            NoteRelationSignal relationSignal,
            int fusedScore
    ) {
        NoteRecallRanker.MetadataRank metadataRank = metadataRanks.getOrDefault(
                source.sourceId(), NoteRecallRanker.MetadataRank.empty());
        int metadataScore = metadataChannelScore + tagChannelScore;
        CandidateSource scoredSource = source.withScore(fusedScore)
                .withRecallSignals(recallSignals(
                        source, metadataRank, metadataChannelScore, tagChannelScore,
                        semanticScore, noteScore, relationSignal))
                .withMetadataRank(metadataRank.matchedFields(), metadataRank.coverageTerms(),
                        metadataRank.coveredQueryTerms(), metadataRank.totalQueryTerms());
        return new NoteRecallRanker.ScoredCandidate(
                scoredSource, metadataScore, semanticScore, noteScore, relationScore, 0, fusedScore);
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
        ).graphNeighborhoodScore = value > 0.0d ? Math.max(1, (int) Math.round(value * 24)) : 0);

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
            int indexedMetadataScore,
            int resolvedTagScore,
            int semanticScore,
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
        if (metadataRank.score() <= 0 && indexedMetadataScore > 0) {
            signals.add("elasticsearch-metadata-recall");
        }
        if (resolvedTagScore > 0) {
            signals.add("resolved-tag");
        }
        if (semanticScore > 0) {
            signals.add("source-semantic-recall");
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

    private void addChannel(Map<String, Integer> fused, List<String> ordered, double weight) {
        addChannel(fused, null, ordered, weight);
    }

    private void addChannel(
            Map<String, Integer> fused,
            Map<String, Integer> part,
            List<String> ordered,
            double weight
    ) {
        if (ordered == null || ordered.isEmpty() || weight <= 0.0d) {
            return;
        }
        int rank = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (String id : ordered) {
            if (id == null || id.isBlank() || !seen.add(id)) {
                continue;
            }
            rank++;
            int contribution = NoteRetrievalStrategyProfile.scaled(
                    weight / (NoteRetrievalStrategyProfile.RRF_K + rank));
            if (contribution <= 0) {
                continue;
            }
            if (fused != null) {
                fused.merge(id, contribution, Integer::sum);
            }
            if (part != null) {
                part.merge(id, contribution, Integer::sum);
            }
        }
    }

    private List<CandidateSource> expandNeighbors(
            String workspaceId, List<CandidateSource> anchors, Set<String> knownIds) {
        if (anchors == null || anchors.isEmpty()) {
            return List.of();
        }
        Set<String> excluded = new LinkedHashSet<>(knownIds);
        List<CandidateSource> frontier = anchors;
        List<CandidateSource> discovered = new ArrayList<>();
        for (int hop = 0; hop < 2 && discovered.size() < NoteRetrievalStrategyProfile.NEIGHBOR_LIMIT; hop++) {
            if (frontier.isEmpty()) {
                break;
            }
            Set<String> frontierIds = new LinkedHashSet<>();
            frontier.forEach(source -> frontierIds.add(source.sourceId()));
            List<String> found = new ArrayList<>();
            found.addAll(emptyIfNull(repository.findTagOverlapSourceIds(
                    workspaceId, tagTerms(frontier), excluded, NoteRetrievalStrategyProfile.RELATION_HOP_LIMIT)));
            found.addAll(emptyIfNull(repository.findTitleSummarySourceIds(
                    workspaceId, titleTerms(frontier), excluded, NoteRetrievalStrategyProfile.RELATION_HOP_LIMIT)));
            found.addAll(emptyIfNull(repository.findNoteCoCitedSourceIds(
                    workspaceId, frontierIds, NoteRetrievalStrategyProfile.RELATION_HOP_LIMIT)));
            found.addAll(emptyIfNull(repository.findTurnCoCitedSourceIds(
                    workspaceId, frontierIds, NoteRetrievalStrategyProfile.RELATION_HOP_LIMIT)));
            List<String> fresh = new ArrayList<>();
            for (String id : found) {
                if (id == null || id.isBlank() || excluded.contains(id)) {
                    continue;
                }
                excluded.add(id);
                fresh.add(id);
                if (discovered.size() + fresh.size() >= NoteRetrievalStrategyProfile.NEIGHBOR_LIMIT) {
                    break;
                }
            }
            if (fresh.isEmpty()) {
                break;
            }
            List<CandidateSource> hydrated = repository.findCurrentSourcesByIds(workspaceId, fresh);
            if (hydrated == null || hydrated.isEmpty()) {
                break;
            }
            discovered.addAll(hydrated);
            frontier = hydrated;
        }
        return discovered;
    }

    private List<String> tagTerms(List<CandidateSource> sources) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (CandidateSource source : sources) {
            for (String tag : relationGraph.tags(source.tagsJson())) {
                addExpansionTerm(terms, tag);
                int separator = tag.indexOf(':');
                if (separator >= 0 && separator + 1 < tag.length()) {
                    addExpansionTerm(terms, tag.substring(separator + 1));
                }
            }
        }
        return capTerms(terms);
    }

    private List<String> titleTerms(List<CandidateSource> sources) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (CandidateSource source : sources) {
            for (String term : LexicalTerms.queryTerms(source.title() + "\n" + source.summary())) {
                addExpansionTerm(terms, term);
            }
        }
        return capTerms(terms);
    }

    private void addExpansionTerm(Set<String> terms, String term) {
        if (term == null || term.isBlank() || GENERIC_EXTENSION_TERMS.contains(term)) {
            return;
        }
        int first = term.codePointAt(0);
        boolean han = Character.UnicodeScript.of(first) == Character.UnicodeScript.HAN;
        if (han) {
            if (term.codePointCount(0, term.length()) < 3) {
                return;
            }
        } else if (term.length() < 4) {
            return;
        }
        terms.add(term);
    }

    private List<String> capTerms(Set<String> terms) {
        return terms.stream()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(term -> term))
                .limit(12)
                .toList();
    }

    private List<String> unionIds(List<List<String>> groups) {
        List<List<String>> safeGroups = groups == null ? List.of() : groups;
        int[] cursor = new int[safeGroups.size()];
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        boolean added;
        do {
            added = false;
            for (int groupIndex = 0; groupIndex < safeGroups.size() && ids.size() < 200; groupIndex++) {
                List<String> group = emptyIfNull(safeGroups.get(groupIndex));
                while (cursor[groupIndex] < group.size()) {
                    String id = group.get(cursor[groupIndex]++);
                    if (id == null || id.isBlank() || !ids.add(id)) {
                        continue;
                    }
                    added = true;
                    break;
                }
            }
        } while (added && ids.size() < 200);
        return List.copyOf(ids);
    }

    private List<String> present(List<String> ids, Set<String> loaded) {
        List<String> result = new ArrayList<>();
        for (String id : emptyIfNull(ids)) {
            if (loaded.contains(id) && !result.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    private List<String> orderByScore(Map<String, Integer> scores) {
        if (scores == null || scores.isEmpty()) {
            return List.of();
        }
        return scores.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> entry) -> entry.getValue()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(NoteRetrievalStrategyProfile.CHANNEL_LIMIT)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<String> emptyIfNull(List<String> values) {
        return values == null ? List.of() : values;
    }

    private Map<String, Set<String>> emptyMap(Map<String, Set<String>> values) {
        return values == null ? Map.of() : values;
    }

    private List<com.noteweave.retrieval.note.NoteSourceSearchPort.NoteSourceHit> metadataHits(
            String workspaceId,
            RetrievalChannels channels
    ) {
        if (channels.hasFieldBoosts()) {
            return sourceSearchPort.metadataRetrieve(workspaceId, channels, 80);
        }
        return sourceSearchPort.metadataRetrieve(workspaceId, channels.lexicalQuery(), 80);
    }

    private static final Set<String> GENERIC_EXTENSION_TERMS = Set.of(
            "md", "pdf", "txt", "doc", "docx", "html", "htm", "json", "xml", "csv", "note", "file", "source");

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
