package com.noteweave.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RetrievalService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RetrievalService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<RetrievedChunk> retrieveForQa(String workspaceId, String query) {
        List<RetrievedChunk> candidates = jdbcTemplate.query("""
                select c.id, c.source_id, c.source_snapshot_id, c.chunk_no, c.heading, c.content, c.location_info,
                       s.title, s.source_type,
                       coalesce(s.summary, '') as summary,
                       coalesce(s.tags_json, '[]') as tags_json,
                       coalesce(s.metadata_json, '{}') as metadata_json
                from source_chunk c
                join source s on s.id = c.source_id
                where c.workspace_id = ? and s.status = 'READY'
                order by s.updated_at desc, c.chunk_no asc
                limit 80
                """, (rs, rowNum) -> new RetrievedChunk(
                rs.getString("id"),
                rs.getString("source_id"),
                rs.getString("source_snapshot_id"),
                rs.getInt("chunk_no"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("location_info"),
                rs.getString("source_type"),
                0,
                ""
        ), workspaceId);
        Set<String> terms = extractTerms(query);
        List<RetrievedChunk> scored = candidates.stream()
                .map(chunk -> {
                    int contentScore = score(chunk.content(), terms) * 3;
                    int metadataScore = score(chunk.title() + "\n" + chunk.sourceType(), terms) * 2;
                    int totalScore = contentScore + metadataScore;
                    return chunk.withScore(totalScore).withMatchReason(qaMatchReason(contentScore, metadataScore));
                })
                .filter(chunk -> chunk.score() > 0 || terms.isEmpty())
                .sorted(Comparator.comparingInt(RetrievedChunk::score).reversed())
                .toList();
        if (!scored.isEmpty()) {
            return selectDiverseEvidence(scored, 6);
        }
        return selectDiverseEvidence(candidates, 6);
    }

    private List<RetrievedChunk> selectDiverseEvidence(List<RetrievedChunk> chunks, int limit) {
        List<RetrievedChunk> selected = new ArrayList<>();
        Set<String> seenSources = new LinkedHashSet<>();
        for (RetrievedChunk chunk : chunks) {
            if (selected.size() >= limit) {
                return selected;
            }
            if (seenSources.add(chunk.sourceId())) {
                selected.add(chunk.withMatchReason(appendReason(chunk.matchReason(), "source-diversity")));
            }
        }
        for (RetrievedChunk chunk : chunks) {
            if (selected.size() >= limit) {
                break;
            }
            boolean exists = selected.stream().anyMatch(item -> item.chunkId().equals(chunk.chunkId()));
            if (!exists) {
                selected.add(chunk);
            }
        }
        return selected;
    }

    public List<CandidateSource> findCandidateSourcesForNote(String workspaceId, String query) {
        return findNoteRecallPlan(workspaceId, query).candidateSources();
    }

    public NoteRecallPlan findNoteRecallPlan(String workspaceId, String query) {
        Set<String> terms = extractTerms(query);
        List<String> rankedTerms = rankTerms(terms);
        Map<String, Integer> noteSignals = noteJournalSignals(workspaceId, terms);
        List<NoteJournalHit> journalHits = findNoteJournalHits(workspaceId, query);
        Map<String, Set<String>> sourceIdsByNote = loadSourceIdsByNote(workspaceId);
        Map<String, Set<String>> sourceIdsByAnsweredTurn = loadSourceIdsByAnsweredTurn(workspaceId);
        List<CandidateSource> candidates = jdbcTemplate.query("""
                select s.id, s.title, s.source_type, s.updated_at,
                       coalesce(s.summary, '') as summary,
                       coalesce(s.tags_json, '[]') as tags_json,
                       coalesce(s.metadata_json, '{}') as metadata_json,
                       count(distinct c.id) as chunk_count,
                       count(distinct w.id) as window_count,
                       coalesce(min(c.content), '') as sample_text
                from source s
                left join source_chunk c on c.source_id = s.id
                left join source_window w on w.source_chunk_id = c.id
                where s.workspace_id = ? and s.status = 'READY'
                group by s.id, s.title, s.source_type, s.updated_at, s.summary, s.tags_json, s.metadata_json
                order by s.updated_at desc
                limit 40
                """, (rs, rowNum) -> new CandidateSource(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("source_type"),
                rs.getInt("chunk_count"),
                rs.getInt("window_count"),
                rs.getString("summary"),
                rs.getString("tags_json"),
                rs.getString("metadata_json"),
                rs.getString("sample_text"),
                0,
                "",
                List.of(),
                List.of(),
                0,
                0,
                "",
                ""
        ), workspaceId);
        Map<String, MetadataRankResult> metadataRanks = new HashMap<>();
        for (CandidateSource source : candidates) {
            metadataRanks.put(source.sourceId(), metadataRank(source, terms, rankedTerms));
        }
        Set<String> anchorSourceIds = new LinkedHashSet<>();
        candidates.stream()
                .filter(source -> metadataRanks.getOrDefault(source.sourceId(), MetadataRankResult.empty()).score() > 0
                        || noteSignals.getOrDefault(source.sourceId(), 0) > 0)
                .sorted(Comparator.comparingInt((CandidateSource source) ->
                        metadataRanks.getOrDefault(source.sourceId(), MetadataRankResult.empty()).score()
                                + noteSignals.getOrDefault(source.sourceId(), 0) * 5).reversed())
                .limit(8)
                .forEach(source -> anchorSourceIds.add(source.sourceId()));
        Map<String, NoteRelationSignal> relationSignals = relationSignalsForNote(
                candidates,
                anchorSourceIds,
                sourceIdsByNote,
                sourceIdsByAnsweredTurn
        );
        List<ScoredCandidateSource> scored = candidates.stream()
                .map(source -> {
                    MetadataRankResult metadataRank = metadataRanks.getOrDefault(source.sourceId(), MetadataRankResult.empty());
                    int metadataScore = metadataRank.score();
                    int noteScore = noteSignals.getOrDefault(source.sourceId(), 0) * 5;
                    NoteRelationSignal relationSignal = relationSignals.getOrDefault(source.sourceId(), NoteRelationSignal.empty(source.sourceId()));
                    int relationScore = relationSignal.score();
                    int readinessScore = windowReadinessScore(source);
                    CandidateSource scoredSource = source.withScore(metadataScore + noteScore + relationScore + readinessScore)
                            .withRecallSignals(recallSignals(source, metadataRank, noteScore, relationSignal))
                            .withMetadataRank(metadataRank.matchedFields(), metadataRank.coverageTerms(),
                                    metadataRank.coveredQueryTerms(), metadataRank.totalQueryTerms());
                    return new ScoredCandidateSource(scoredSource, metadataScore, noteScore, relationScore, readinessScore);
                })
                .filter(source -> source.score() > 0 || terms.isEmpty())
                .sorted(Comparator.comparingInt(ScoredCandidateSource::score).reversed())
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
        List<ScoredCandidateSource> candidateSources = selectCandidateSources(scored, 4);
        Set<String> selectedIds = new LinkedHashSet<>(candidateSources.stream().map(ScoredCandidateSource::sourceId).toList());
        List<ScoredCandidateSource> relationExpansion = scored.stream()
                .filter(source -> !selectedIds.contains(source.sourceId()) && source.relationScore() > 0)
                .sorted(Comparator.comparingInt(ScoredCandidateSource::relationScore).reversed()
                        .thenComparing(Comparator.comparingInt(ScoredCandidateSource::score).reversed()))
                .map(source -> source.withSelectionReason("relation-expansion"))
                .limit(3)
                .toList();
        List<CandidateSource> verifySources = buildVerifyBatch(candidateSources, relationExpansion, 6);
        return new NoteRecallPlan(
                journalHits,
                candidateSources.stream().map(ScoredCandidateSource::source).toList(),
                relationExpansion.stream().map(ScoredCandidateSource::source).toList(),
                verifySources,
                new NoteRecallTrace(
                        journalHits.size(),
                        candidateSources.size(),
                        relationExpansion.size(),
                        verifySources.size(),
                        candidateSources.stream().mapToInt(ScoredCandidateSource::metadataScore).sum(),
                        candidateSources.stream().mapToInt(ScoredCandidateSource::noteScore).sum(),
                        candidateSources.stream().mapToInt(ScoredCandidateSource::relationScore).sum(),
                        candidateSources.stream().mapToInt(ScoredCandidateSource::readinessScore).sum()
                )
        );
    }

    private List<ScoredCandidateSource> selectCandidateSources(List<ScoredCandidateSource> scored, int limit) {
        if (scored.isEmpty()) {
            return List.of();
        }
        List<ScoredCandidateSource> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        takeByQuota(selected, seen, scored, limit, source -> source.noteScore() > 0, 1, "journal-quota");
        takeByQuota(selected, seen, scored, limit, source -> source.metadataScore() > 0, 2, "metadata-quota");
        takeByQuota(selected, seen, scored, limit, source -> source.relationScore() > 0, 1, "relation-quota");
        takeBySourceTypeQuota(selected, seen, scored, limit, 2, "source-type-quota");
        for (ScoredCandidateSource source : scored) {
            if (selected.size() >= limit) {
                break;
            }
            if (seen.add(source.sourceId())) {
                selected.add(source.withSelectionReason("top-score-backfill"));
            }
        }
        return selected;
    }

    private void takeByQuota(
            List<ScoredCandidateSource> selected,
            Set<String> seen,
            List<ScoredCandidateSource> scored,
            int limit,
            java.util.function.Predicate<ScoredCandidateSource> predicate,
            int quota,
            String selectionReason
    ) {
        int taken = 0;
        for (ScoredCandidateSource source : scored) {
            if (selected.size() >= limit || taken >= quota) {
                return;
            }
            if (seen.contains(source.sourceId()) || !predicate.test(source)) {
                continue;
            }
            seen.add(source.sourceId());
            selected.add(source.withSelectionReason(selectionReason));
            taken++;
        }
    }

    private void takeBySourceTypeQuota(
            List<ScoredCandidateSource> selected,
            Set<String> seen,
            List<ScoredCandidateSource> scored,
            int limit,
            int quota,
            String selectionReason
    ) {
        int taken = 0;
        Set<String> seenTypes = new LinkedHashSet<>();
        for (ScoredCandidateSource source : selected) {
            seenTypes.add(normalizeSourceType(source.source().sourceType()));
        }
        for (ScoredCandidateSource source : scored) {
            if (selected.size() >= limit || taken >= quota) {
                return;
            }
            String sourceType = normalizeSourceType(source.source().sourceType());
            if (seen.contains(source.sourceId()) || seenTypes.contains(sourceType)) {
                continue;
            }
            seen.add(source.sourceId());
            seenTypes.add(sourceType);
            selected.add(source.withSelectionReason(selectionReason));
            taken++;
        }
    }

    private List<CandidateSource> buildVerifyBatch(
            List<ScoredCandidateSource> candidates,
            List<ScoredCandidateSource> expansions,
            int limit
    ) {
        List<CandidateSource> verify = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> seenTypes = new LinkedHashSet<>();
        for (ScoredCandidateSource source : candidates) {
            if (verify.size() >= limit) {
                break;
            }
            if (source.source().windowCount() <= 0) {
                continue;
            }
            if (seen.add(source.sourceId())) {
                String verifyReason = source.selectionReason() == null || source.selectionReason().isBlank()
                        ? "candidate:top-score-backfill"
                        : "candidate:" + source.selectionReason();
                verify.add(source.source().withVerifyAdmissionReason(verifyReason));
                seenTypes.add(normalizeSourceType(source.source().sourceType()));
            }
        }
        for (ScoredCandidateSource source : expansions) {
            if (verify.size() >= limit) {
                break;
            }
            String sourceType = normalizeSourceType(source.source().sourceType());
            if (source.source().windowCount() <= 0 || seen.contains(source.sourceId()) || seenTypes.contains(sourceType)) {
                continue;
            }
            if (seen.add(source.sourceId())) {
                seenTypes.add(sourceType);
                verify.add(source.source().withVerifyAdmissionReason("relation-expansion:source-type-quota"));
            }
        }
        for (ScoredCandidateSource source : expansions) {
            if (verify.size() >= limit) {
                break;
            }
            if (source.source().windowCount() <= 0) {
                continue;
            }
            if (seen.add(source.sourceId())) {
                verify.add(source.source().withVerifyAdmissionReason("relation-expansion"));
            }
        }
        for (ScoredCandidateSource source : candidates) {
            if (verify.size() >= limit) {
                break;
            }
            if (seen.add(source.sourceId())) {
                String verifyReason = source.selectionReason() == null || source.selectionReason().isBlank()
                        ? "candidate:top-score-backfill"
                        : "candidate:" + source.selectionReason();
                verify.add(source.source().withVerifyAdmissionReason(verifyReason + ":window-fallback"));
            }
        }
        for (ScoredCandidateSource source : expansions) {
            if (verify.size() >= limit) {
                break;
            }
            if (seen.add(source.sourceId())) {
                verify.add(source.source().withVerifyAdmissionReason("relation-expansion:window-fallback"));
            }
        }
        return verify;
    }

    public List<ReadingWindow> openSourceWindowsForNote(String workspaceId, List<CandidateSource> sources, String query) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        Set<String> terms = extractTerms(query);
        List<ReadingWindow> selected = new ArrayList<>();
        for (CandidateSource source : sources) {
            selected.addAll(selectReadPlanForSource(loadScoredWindowsForSource(workspaceId, source.sourceId(), terms), 3));
        }
        return selected.stream()
                .sorted(Comparator.comparingInt((ReadingWindow window) -> readRolePriority(window.readRole()))
                        .thenComparing(Comparator.comparingInt(ReadingWindow::score).reversed())
                        .thenComparing(ReadingWindow::title)
                        .thenComparingInt(ReadingWindow::chunkNo)
                        .thenComparingInt(ReadingWindow::windowNo))
                .limit(8)
                .toList();
    }

    public List<NoteEntryMetadata> readEntriesMetadataForNote(String workspaceId, List<CandidateSource> sources, String query) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        List<NoteEntryMetadata> metadataEntries = new ArrayList<>();
        for (CandidateSource source : sources) {
            SourceStateSnapshot sourceState = loadSourceState(workspaceId, source.sourceId());
            Integer windowCount = jdbcTemplate.queryForObject("""
                    select count(*)
                    from source_window w
                    join source_chunk c on c.id = w.source_chunk_id
                    where c.workspace_id = ? and c.source_id = ?
                    """, Integer.class, workspaceId, source.sourceId());
            List<WindowLocator> windowLocators = windowLocatorsForSource(workspaceId, source.sourceId(), query);
            int totalWindowCount = windowCount == null ? 0 : windowCount;
            metadataEntries.add(new NoteEntryMetadata(
                    source.sourceId(),
                    source.title(),
                    source.sourceType(),
                    source.summary(),
                    parseJsonStringArray(source.tagsJson()),
                    metadataPreview(source.metadataJson()),
                    sourceState.parseStatus(),
                    sourceState.indexStatus(),
                    source.chunkCount(),
                    totalWindowCount,
                    totalWindowCount > windowLocators.size(),
                    windowLocators,
                    relatedEntriesForSource(workspaceId, source)
            ));
        }
        return metadataEntries;
    }

    public List<NoteJournalHit> findNoteJournalHits(String workspaceId, String query) {
        Set<String> terms = extractTerms(query);
        Map<String, NoteJournalFreshness> freshnessByNoteId = loadNoteJournalFreshness(workspaceId);
        List<NoteJournalHit> hits = jdbcTemplate.query("""
                select i.id, i.title, coalesce(v.summary, '') as summary, v.content,
                       count(c.id) as citation_count
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                left join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                left join citation c on c.id = kvc.citation_id
                where i.workspace_id = ? and i.item_type = 'NOTE' and i.status = 'ACTIVE'
                group by i.id, i.title, v.summary, v.content, i.updated_at
                order by i.updated_at desc
                limit 30
                """, (rs, rowNum) -> new NoteJournalHit(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("content"),
                rs.getInt("citation_count"),
                0,
                "fresh",
                "引用资料保持最新，可直接复用。",
                0,
                0
        ), workspaceId);
        return hits.stream()
                .map(hit -> {
                    NoteJournalFreshness freshness = freshnessByNoteId.getOrDefault(hit.noteId(), NoteJournalFreshness.fresh());
                    int baseScore = score(hit.title() + "\n" + hit.summary() + "\n" + hit.content(), terms);
                    int adjustedScore = Math.max(0, baseScore - freshness.penalty());
                    return hit.withFreshness(freshness.freshnessStatus(), freshness.freshnessNote(),
                                    freshness.staleSourceCount(), freshness.unavailableSourceCount())
                            .withScore(adjustedScore);
                })
                .filter(hit -> hit.score() > 0 || terms.isEmpty())
                .sorted(Comparator.comparingInt((NoteJournalHit hit) -> journalFreshnessPriority(hit.freshnessStatus()))
                        .thenComparing(Comparator.comparingInt(NoteJournalHit::score).reversed())
                        .thenComparing(NoteJournalHit::title))
                .limit(3)
                .toList();
    }

    private Map<String, Integer> noteJournalSignals(String workspaceId, Set<String> terms) {
        if (terms.isEmpty()) {
            return Map.of();
        }
        Map<String, NoteJournalFreshness> freshnessByNoteId = loadNoteJournalFreshness(workspaceId);
        Map<String, Integer> signals = new HashMap<>();
        List<NoteJournalSourceSignal> rows = jdbcTemplate.query("""
                select i.id as note_id, c.source_id, i.title, coalesce(v.summary, '') as summary, v.content
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                join citation c on c.id = kvc.citation_id
                where i.workspace_id = ? and i.item_type = 'NOTE' and i.status = 'ACTIVE'
                """, (rs, rowNum) -> new NoteJournalSourceSignal(
                rs.getString("note_id"),
                rs.getString("source_id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("content")
        ), workspaceId);
        for (NoteJournalSourceSignal row : rows) {
            int rowScore = score(row.title() + "\n" + row.summary() + "\n" + row.content(), terms);
            NoteJournalFreshness freshness = freshnessByNoteId.getOrDefault(row.noteId(), NoteJournalFreshness.fresh());
            if ("source-unavailable".equals(freshness.freshnessStatus())) {
                continue;
            }
            if ("stale-source-updated".equals(freshness.freshnessStatus())) {
                rowScore = Math.max(1, rowScore / 2);
            }
            if (rowScore > 0) {
                signals.merge(row.sourceId(), rowScore, Integer::sum);
            }
        }
        return signals;
    }

    private Map<String, NoteJournalFreshness> loadNoteJournalFreshness(String workspaceId) {
        List<NoteJournalFreshnessRow> rows = jdbcTemplate.query("""
                select i.id as note_id,
                       sum(case when s.id is not null and s.status <> 'READY' then 1 else 0 end) as unavailable_source_count,
                       sum(case when s.id is not null and s.status = 'READY' and s.updated_at > v.created_at then 1 else 0 end) as stale_source_count
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                left join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                left join citation c on c.id = kvc.citation_id
                left join source s on s.id = c.source_id
                where i.workspace_id = ? and i.item_type = 'NOTE' and i.status = 'ACTIVE'
                group by i.id, v.created_at
                """, (rs, rowNum) -> new NoteJournalFreshnessRow(
                rs.getString("note_id"),
                rs.getInt("stale_source_count"),
                rs.getInt("unavailable_source_count")
        ), workspaceId);
        Map<String, NoteJournalFreshness> freshnessByNoteId = new HashMap<>();
        for (NoteJournalFreshnessRow row : rows) {
            freshnessByNoteId.put(row.noteId(), NoteJournalFreshness.from(row.staleSourceCount(), row.unavailableSourceCount()));
        }
        return freshnessByNoteId;
    }

    private int journalFreshnessPriority(String freshnessStatus) {
        return switch (freshnessStatus) {
            case "fresh" -> 0;
            case "stale-source-updated" -> 1;
            case "source-unavailable" -> 2;
            default -> 3;
        };
    }

    private Map<String, NoteRelationSignal> relationSignalsForNote(
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
            tagsBySource.put(source.sourceId(), extractTags(source.tagsJson()));
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
                    source.sourceId(),
                    ignored -> new NoteRelationSignalAccumulator(source.sourceId())
            );
            accumulator.sharedTagCount += tagOverlap;
            int titleOverlap = 0;
            for (String anchorId : anchorSourceIds) {
                CandidateSource anchor = sourcesById.get(anchorId);
                if (anchor == null) {
                    continue;
                }
                titleOverlap = Math.max(titleOverlap, overlapTerms(source.title() + "\n" + source.summary(), anchor.title() + "\n" + anchor.summary()));
            }
            accumulator.titleOverlapScore = Math.max(accumulator.titleOverlapScore, titleOverlap);
        }
        Map<String, Integer> coCitation = coOccurrenceSignals(anchorSourceIds, sourceIdsByNote);
        coCitation.forEach((sourceId, value) -> accumulators.computeIfAbsent(
                sourceId,
                ignored -> new NoteRelationSignalAccumulator(sourceId)
        ).coCitedNoteCount += value);
        Map<String, Integer> turnCoCitation = coOccurrenceSignals(anchorSourceIds, sourceIdsByAnsweredTurn);
        turnCoCitation.forEach((sourceId, value) -> accumulators.computeIfAbsent(
                sourceId,
                ignored -> new NoteRelationSignalAccumulator(sourceId)
        ).coCitedTurnCount += value);
        Map<String, Map<String, Integer>> relationGraph = buildSourceRelationGraph(sources, sourceIdsByNote, sourceIdsByAnsweredTurn);
        Map<String, Double> graphScores = propagateRelationGraph(relationGraph, anchorSourceIds, 4, 0.2d);
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

    private Map<String, Integer> coOccurrenceSignals(Set<String> anchorSourceIds, Map<String, Set<String>> sourceIdsByGroup) {
        if (anchorSourceIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> signals = new HashMap<>();
        for (Set<String> sourceIds : sourceIdsByGroup.values()) {
            boolean hasAnchor = sourceIds.stream().anyMatch(anchorSourceIds::contains);
            if (!hasAnchor) {
                continue;
            }
            for (String sourceId : sourceIds) {
                if (!anchorSourceIds.contains(sourceId)) {
                    signals.merge(sourceId, 1, Integer::sum);
                }
            }
        }
        return signals;
    }

    private Map<String, Set<String>> loadSourceIdsByNote(String workspaceId) {
        List<NoteCitationPair> rows = jdbcTemplate.query("""
                select i.id as note_id, c.source_id
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                join citation c on c.id = kvc.citation_id
                where i.workspace_id = ? and i.item_type = 'NOTE' and i.status = 'ACTIVE'
                """, (rs, rowNum) -> new NoteCitationPair(
                rs.getString("note_id"),
                rs.getString("source_id")
        ), workspaceId);
        Map<String, Set<String>> sourceIdsByNote = new HashMap<>();
        for (NoteCitationPair row : rows) {
            sourceIdsByNote.computeIfAbsent(row.noteId(), ignored -> new LinkedHashSet<>()).add(row.sourceId());
        }
        return sourceIdsByNote;
    }

    private Map<String, Set<String>> loadSourceIdsByAnsweredTurn(String workspaceId) {
        List<MessageCitationPair> rows = jdbcTemplate.query("""
                select m.id as message_id, c.source_id
                from conversation_message m
                join message_citation mc on mc.message_id = m.id
                join citation c on c.id = mc.citation_id
                where m.workspace_id = ? and m.role = 'ASSISTANT'
                """, (rs, rowNum) -> new MessageCitationPair(
                rs.getString("message_id"),
                rs.getString("source_id")
        ), workspaceId);
        Map<String, Set<String>> sourceIdsByMessage = new HashMap<>();
        for (MessageCitationPair row : rows) {
            sourceIdsByMessage.computeIfAbsent(row.messageId(), ignored -> new LinkedHashSet<>()).add(row.sourceId());
        }
        return sourceIdsByMessage;
    }

    private Map<String, Map<String, Integer>> buildSourceRelationGraph(
            List<CandidateSource> sources,
            Map<String, Set<String>> sourceIdsByNote,
            Map<String, Set<String>> sourceIdsByAnsweredTurn
    ) {
        Map<String, Map<String, Integer>> graph = new HashMap<>();
        Map<String, Set<String>> tagsBySource = new HashMap<>();
        for (CandidateSource source : sources) {
            graph.put(source.sourceId(), new HashMap<>());
            tagsBySource.put(source.sourceId(), extractTags(source.tagsJson()));
        }
        for (int i = 0; i < sources.size(); i++) {
            CandidateSource left = sources.get(i);
            for (int j = i + 1; j < sources.size(); j++) {
                CandidateSource right = sources.get(j);
                int sharedTagWeight = sharedTagWeight(
                        tagsBySource.getOrDefault(left.sourceId(), Set.of()),
                        tagsBySource.getOrDefault(right.sourceId(), Set.of())
                );
                int titleWeight = overlapTerms(left.title() + "\n" + left.summary(), right.title() + "\n" + right.summary());
                int coCitedWeight = coCitationWeight(sourceIdsByNote, left.sourceId(), right.sourceId());
                int turnCoCitedWeight = coCitationWeight(sourceIdsByAnsweredTurn, left.sourceId(), right.sourceId());
                int totalWeight = sharedTagWeight * 2 + coCitedWeight * 3 + turnCoCitedWeight * 4 + titleWeight;
                if (totalWeight <= 0) {
                    continue;
                }
                graph.computeIfAbsent(left.sourceId(), ignored -> new HashMap<>()).merge(right.sourceId(), totalWeight, Integer::sum);
                graph.computeIfAbsent(right.sourceId(), ignored -> new HashMap<>()).merge(left.sourceId(), totalWeight, Integer::sum);
            }
        }
        return graph;
    }

    private int sharedTagWeight(Set<String> leftTags, Set<String> rightTags) {
        int overlap = 0;
        for (String tag : leftTags) {
            if (rightTags.contains(tag)) {
                overlap++;
            }
        }
        return overlap;
    }

    private int coCitationWeight(Map<String, Set<String>> sourceIdsByNote, String leftSourceId, String rightSourceId) {
        int count = 0;
        for (Set<String> sourceIds : sourceIdsByNote.values()) {
            if (sourceIds.contains(leftSourceId) && sourceIds.contains(rightSourceId)) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Double> propagateRelationGraph(
            Map<String, Map<String, Integer>> graph,
            Set<String> anchorSourceIds,
            int steps,
            double restartAlpha
    ) {
        if (graph.isEmpty() || anchorSourceIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> seed = new HashMap<>();
        double seedWeight = 1.0d / anchorSourceIds.size();
        for (String anchorSourceId : anchorSourceIds) {
            if (graph.containsKey(anchorSourceId)) {
                seed.put(anchorSourceId, seedWeight);
            }
        }
        if (seed.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> current = new HashMap<>(seed);
        for (int step = 0; step < steps; step++) {
            Map<String, Double> next = new HashMap<>();
            for (Map.Entry<String, Double> entry : seed.entrySet()) {
                next.merge(entry.getKey(), restartAlpha * entry.getValue(), Double::sum);
            }
            for (Map.Entry<String, Double> entry : current.entrySet()) {
                Map<String, Integer> neighbors = graph.getOrDefault(entry.getKey(), Map.of());
                if (neighbors.isEmpty()) {
                    next.merge(entry.getKey(), (1.0d - restartAlpha) * entry.getValue(), Double::sum);
                    continue;
                }
                int totalWeight = neighbors.values().stream().mapToInt(Integer::intValue).sum();
                if (totalWeight <= 0) {
                    continue;
                }
                for (Map.Entry<String, Integer> neighbor : neighbors.entrySet()) {
                    double contribution = (1.0d - restartAlpha) * entry.getValue() * neighbor.getValue() / totalWeight;
                    next.merge(neighbor.getKey(), contribution, Double::sum);
                }
            }
            current = next;
        }
        Map<String, Double> filtered = new HashMap<>();
        current.forEach((sourceId, value) -> {
            if (!anchorSourceIds.contains(sourceId) && value > 0) {
                filtered.put(sourceId, value);
            }
        });
        return filtered;
    }

    private Set<String> extractTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return Set.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\"([^\"]{1,80})\"").matcher(tagsJson.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tags.add(matcher.group(1));
        }
        return tags;
    }

    private List<String> parseJsonStringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank()).limit(8).toList();
        } catch (Exception ignored) {
            return new ArrayList<>(extractTags(json)).stream().limit(8).toList();
        }
    }

    private List<String> metadataPreview(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {
            });
            List<String> preview = new ArrayList<>();
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                preview.add(entry.getKey() + "=" + entry.getValue());
                if (preview.size() >= 6) {
                    break;
                }
            }
            return preview;
        } catch (Exception ignored) {
            return List.of(metadataJson);
        }
    }

    private List<WindowLocator> windowLocatorsForSource(String workspaceId, String sourceId, String query) {
        Set<String> terms = extractTerms(query);
        return selectReadPlanForSource(loadScoredWindowsForSource(workspaceId, sourceId, terms), 4).stream()
                .map(window -> new WindowLocator(
                        window.chunkNo(),
                        window.heading(),
                        window.windowNo(),
                        window.locationInfo(),
                        window.score(),
                        window.readRole(),
                        "continuation-window".equals(window.readRole()) ? window.anchorWindowNo() : null,
                        window.readObjective()
                ))
                .toList();
    }

    private SourceStateSnapshot loadSourceState(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select parse_status, index_status
                from source
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                return new SourceStateSnapshot("UNKNOWN", "UNKNOWN");
            }
            return new SourceStateSnapshot(
                    rs.getString("parse_status"),
                    rs.getString("index_status")
            );
        }, workspaceId, sourceId);
    }

    private List<RelatedEntryPreview> relatedEntriesForSource(String workspaceId, CandidateSource anchor) {
        Map<String, Integer> sharedTagCounts = new HashMap<>();
        Map<String, Integer> lexicalOverlapScores = new HashMap<>();
        Set<String> anchorTags = extractTags(anchor.tagsJson());
        Map<String, Set<String>> sourceIdsByAnsweredTurn = loadSourceIdsByAnsweredTurn(workspaceId);
        List<CandidateSource> relationCandidates = new ArrayList<>();
        relationCandidates.add(anchor);
        if (!anchorTags.isEmpty()) {
            List<SourceRelationRow> rows = jdbcTemplate.query("""
                    select s.id, s.title, coalesce(s.tags_json, '[]') as tags_json
                    from source s
                    where s.workspace_id = ? and s.status = 'READY' and s.id <> ?
                    order by s.updated_at desc
                    limit 30
                    """, (rs, rowNum) -> new SourceRelationRow(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("tags_json")
            ), workspaceId, anchor.sourceId());
            for (SourceRelationRow row : rows) {
                relationCandidates.add(new CandidateSource(
                        row.sourceId(),
                        row.title(),
                        "SOURCE",
                        0,
                        0,
                        "",
                        row.tagsJson(),
                        "{}",
                        "",
                        0,
                        "",
                        List.of(),
                        List.of(),
                        0,
                        0,
                        "",
                        ""
                ));
                Set<String> otherTags = extractTags(row.tagsJson());
                int overlap = 0;
                for (String tag : anchorTags) {
                    if (otherTags.contains(tag)) {
                        overlap++;
                    }
                }
                if (overlap > 0) {
                    sharedTagCounts.put(row.sourceId(), overlap);
                }
                int lexicalOverlap = overlapTerms(anchor.title() + "\n" + anchor.summary(), row.title());
                if (lexicalOverlap > 0) {
                    lexicalOverlapScores.put(row.sourceId(), lexicalOverlap);
                }
            }
        }

        Map<String, Set<String>> sourceIdsByNote = loadSourceIdsByNote(workspaceId);
        Map<String, Map<String, Integer>> relationGraph = buildSourceRelationGraph(
                relationCandidates,
                sourceIdsByNote,
                sourceIdsByAnsweredTurn
        );
        Map<String, Double> graphScores = propagateRelationGraph(relationGraph, Set.of(anchor.sourceId()), 4, 0.2d);
        Map<String, Integer> coCitationCounts = new HashMap<>();
        Map<String, Integer> coCitedTurnCounts = coOccurrenceSignals(Set.of(anchor.sourceId()), sourceIdsByAnsweredTurn);
        List<RelatedEntryPreview> related = jdbcTemplate.query("""
                select c2.source_id, s.title, count(distinct i.id) as note_count
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                join knowledge_version_citation kvc1 on kvc1.knowledge_version_id = v.id
                join citation c1 on c1.id = kvc1.citation_id
                join knowledge_version_citation kvc2 on kvc2.knowledge_version_id = v.id
                join citation c2 on c2.id = kvc2.citation_id
                join source s on s.id = c2.source_id
                where i.workspace_id = ?
                  and i.item_type = 'NOTE'
                  and i.status = 'ACTIVE'
                  and c1.source_id = ?
                  and c2.source_id <> ?
                group by c2.source_id, s.title
                order by note_count desc, s.title asc
                """, (rs, rowNum) -> {
                    String sourceId = rs.getString("source_id");
                    int coCitedNotes = rs.getInt("note_count");
                    coCitationCounts.put(sourceId, coCitedNotes);
                    int sharedTags = sharedTagCounts.getOrDefault(sourceId, 0);
                    int lexicalOverlap = lexicalOverlapScores.getOrDefault(sourceId, 0);
                    int graphNeighborhoodScore = Math.max(0, (int) Math.round(graphScores.getOrDefault(sourceId, 0.0d) * 24));
                    int coCitedTurns = coCitedTurnCounts.getOrDefault(sourceId, 0);
                    return new RelatedEntryPreview(
                            sourceId,
                            rs.getString("title"),
                            sharedTags,
                            coCitedNotes,
                            coCitedTurns,
                            lexicalOverlap,
                            graphNeighborhoodScore,
                            relatedReason(sharedTags, coCitedNotes, coCitedTurns, lexicalOverlap, graphNeighborhoodScore),
                            sharedTags * 2 + coCitedNotes * 3 + coCitedTurns * 4 + lexicalOverlap + graphNeighborhoodScore
                    );
                }, workspaceId, anchor.sourceId(), anchor.sourceId());

        Map<String, RelatedEntryPreview> bySourceId = new HashMap<>();
        for (RelatedEntryPreview item : related) {
            bySourceId.put(item.sourceId(), item);
        }
        for (Map.Entry<String, Integer> entry : sharedTagCounts.entrySet()) {
            if (!bySourceId.containsKey(entry.getKey())) {
                String title = jdbcTemplate.queryForObject("""
                        select title from source
                        where workspace_id = ? and id = ? and status = 'READY'
                        """, String.class, workspaceId, entry.getKey());
                bySourceId.put(entry.getKey(), new RelatedEntryPreview(
                        entry.getKey(),
                        title == null ? entry.getKey() : title,
                        entry.getValue(),
                        coCitationCounts.getOrDefault(entry.getKey(), 0),
                        coCitedTurnCounts.getOrDefault(entry.getKey(), 0),
                        lexicalOverlapScores.getOrDefault(entry.getKey(), 0),
                        Math.max(0, (int) Math.round(graphScores.getOrDefault(entry.getKey(), 0.0d) * 24)),
                        relatedReason(
                                entry.getValue(),
                                coCitationCounts.getOrDefault(entry.getKey(), 0),
                                coCitedTurnCounts.getOrDefault(entry.getKey(), 0),
                                lexicalOverlapScores.getOrDefault(entry.getKey(), 0),
                                Math.max(0, (int) Math.round(graphScores.getOrDefault(entry.getKey(), 0.0d) * 24))
                        ),
                        entry.getValue() * 2 + coCitationCounts.getOrDefault(entry.getKey(), 0) * 3
                                + coCitedTurnCounts.getOrDefault(entry.getKey(), 0) * 4
                                + lexicalOverlapScores.getOrDefault(entry.getKey(), 0)
                                + Math.max(0, (int) Math.round(graphScores.getOrDefault(entry.getKey(), 0.0d) * 24))
                ));
            }
        }
        return bySourceId.values().stream()
                .sorted(Comparator.comparingInt(RelatedEntryPreview::score).reversed()
                        .thenComparing(RelatedEntryPreview::title))
                .limit(3)
                .toList();
    }

    private String relatedReason(
            int sharedTagCount,
            int coCitedNoteCount,
            int coCitedTurnCount,
            int lexicalOverlapScore,
            int graphNeighborhoodScore
    ) {
        List<String> reasons = new ArrayList<>();
        if (sharedTagCount > 0) {
            reasons.add("shared-tags");
        }
        if (coCitedNoteCount > 0) {
            reasons.add("co-cited-notes");
        }
        if (coCitedTurnCount > 0) {
            reasons.add("co-cited-turns");
        }
        if (lexicalOverlapScore > 0) {
            reasons.add("title-summary-neighbor");
        }
        if (graphNeighborhoodScore > 0) {
            reasons.add("graph-neighbor");
        }
        return reasons.isEmpty() ? "workspace-neighbor" : String.join(", ", reasons);
    }

    private String recallSignals(CandidateSource source, MetadataRankResult metadataRank, int noteScore, NoteRelationSignal relationSignal) {
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

    private String qaMatchReason(int contentScore, int metadataScore) {
        List<String> reasons = new ArrayList<>();
        if (contentScore > 0) {
            reasons.add("chunk-keyword");
        }
        if (metadataScore > 0) {
            reasons.add("metadata-filter");
        }
        if (reasons.isEmpty()) {
            reasons.add("workspace-recent");
        }
        return String.join(", ", reasons);
    }

    private String appendReason(String current, String reason) {
        if (current == null || current.isBlank()) {
            return reason;
        }
        if (current.contains(reason)) {
            return current;
        }
        return current + ", " + reason;
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

    private int score(String content, Set<String> terms) {
        if (terms.isEmpty()) {
            return 1;
        }
        String lower = content == null ? "" : content.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (lower.contains(term)) {
                score += Math.max(1, term.length());
            }
        }
        return score;
    }

    private MetadataRankResult metadataRank(CandidateSource source, Set<String> rawTerms, List<String> rankedTerms) {
        List<String> queryTerms = rankedTerms.isEmpty() ? new ArrayList<>(rawTerms) : rankedTerms;
        if (queryTerms.isEmpty()) {
            return MetadataRankResult.empty();
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
        return new MetadataRankResult(
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

    private String normalizeSourceType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? "UNKNOWN" : normalized;
    }

    private int overlapTerms(String left, String right) {
        Set<String> leftTerms = extractTerms(left);
        Set<String> rightTerms = extractTerms(right);
        int overlap = 0;
        for (String term : leftTerms) {
            if (rightTerms.contains(term)) {
                overlap += Math.max(1, term.length());
            }
        }
        return overlap;
    }

    private List<ReadingWindow> loadScoredWindowsForSource(String workspaceId, String sourceId, Set<String> terms) {
        return jdbcTemplate.query("""
                select c.id, c.source_id, c.source_snapshot_id, c.chunk_no, coalesce(c.heading, '') as heading,
                       s.title, w.window_no, w.content, w.location_info
                from source_chunk c
                join source s on s.id = c.source_id
                join source_window w on w.source_chunk_id = c.id
                where c.workspace_id = ? and c.source_id = ?
                order by c.chunk_no asc, w.window_no asc
                limit 16
                """, (rs, rowNum) -> new ReadingWindow(
                rs.getString("id"),
                rs.getString("source_id"),
                rs.getString("source_snapshot_id"),
                rs.getInt("chunk_no"),
                rs.getString("heading"),
                rs.getString("title"),
                rs.getInt("window_no"),
                rs.getString("content"),
                rs.getString("location_info"),
                score(rs.getString("heading") + "\n" + rs.getString("content") + "\n" + rs.getString("location_info"), terms),
                "candidate-window",
                rs.getInt("window_no"),
                "candidate-pool"
        ), workspaceId, sourceId);
    }

    private List<ReadingWindow> selectReadPlanForSource(List<ReadingWindow> windows, int limit) {
        if (windows.isEmpty()) {
            return List.of();
        }
        List<ReadingWindow> ranked = windows.stream()
                .sorted(Comparator.comparingInt(ReadingWindow::score).reversed()
                        .thenComparingInt(ReadingWindow::chunkNo)
                        .thenComparingInt(ReadingWindow::windowNo))
                .toList();
        ReadingWindow primary = ranked.get(0)
                .withReadRole("primary-window")
                .withAnchorWindowNo(ranked.get(0).windowNo())
                .withReadObjective("best-evidence");
        List<ReadingWindow> plan = new ArrayList<>();
        plan.add(primary);
        Set<String> seen = new LinkedHashSet<>();
        seen.add(windowKey(primary));

        windows.stream()
                .filter(window -> ((window.chunkNo() == primary.chunkNo()
                                && Math.abs(window.windowNo() - primary.windowNo()) == 1)
                        || (Math.abs(window.chunkNo() - primary.chunkNo()) == 1
                                && window.windowNo() == primary.windowNo()))
                        && seen.add(windowKey(window)))
                .sorted(Comparator.comparingInt((ReadingWindow window) -> Math.abs(window.chunkNo() - primary.chunkNo()) * 10
                                + Math.abs(window.windowNo() - primary.windowNo()))
                        .thenComparing(Comparator.comparingInt(ReadingWindow::score).reversed()))
                .limit(Math.max(0, limit - 1))
                .map(window -> window.withReadRole("continuation-window")
                        .withAnchorWindowNo(primary.windowNo())
                        .withReadObjective("adjacent-context"))
                .forEach(plan::add);

        for (ReadingWindow window : ranked) {
            if (plan.size() >= limit) {
                break;
            }
            if (seen.add(windowKey(window))) {
                plan.add(window.withReadRole("secondary-window")
                        .withAnchorWindowNo(window.windowNo())
                        .withReadObjective("secondary-evidence"));
            }
        }
        return plan;
    }

    private String windowKey(ReadingWindow window) {
        return window.sourceId() + ":" + window.chunkNo() + ":" + window.windowNo();
    }

    private int readRolePriority(String readRole) {
        return switch (readRole == null ? "" : readRole) {
            case "primary-window" -> 3;
            case "continuation-window" -> 2;
            case "secondary-window" -> 1;
            default -> 0;
        };
    }

    public record RetrievedChunk(
            String chunkId,
            String sourceId,
            String sourceSnapshotId,
            int chunkNo,
            String title,
            String content,
            String locationInfo,
            String sourceType,
            int score,
            String matchReason
    ) {
        RetrievedChunk withScore(int nextScore) {
            return new RetrievedChunk(chunkId, sourceId, sourceSnapshotId, chunkNo, title, content, locationInfo, sourceType, nextScore, matchReason);
        }

        RetrievedChunk withMatchReason(String nextMatchReason) {
            return new RetrievedChunk(chunkId, sourceId, sourceSnapshotId, chunkNo, title, content, locationInfo, sourceType, score, nextMatchReason);
        }
    }

    public record CandidateSource(
            String sourceId,
            String title,
            String sourceType,
            int chunkCount,
            int windowCount,
            String summary,
            String tagsJson,
            String metadataJson,
            String sampleText,
            int score,
            String recallSignals,
            List<String> matchedFields,
            List<String> coverageTerms,
            int coveredQueryTerms,
            int totalQueryTerms,
            String selectionReason,
            String verifyAdmissionReason
    ) {
        CandidateSource withScore(int nextScore) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, windowCount, summary, tagsJson, metadataJson, sampleText,
                    nextScore, recallSignals, matchedFields, coverageTerms, coveredQueryTerms, totalQueryTerms,
                    selectionReason, verifyAdmissionReason);
        }

        CandidateSource withRecallSignals(String nextRecallSignals) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, windowCount, summary, tagsJson, metadataJson, sampleText,
                    score, nextRecallSignals, matchedFields, coverageTerms, coveredQueryTerms, totalQueryTerms,
                    selectionReason, verifyAdmissionReason);
        }

        CandidateSource withMetadataRank(
                List<String> nextMatchedFields,
                List<String> nextCoverageTerms,
                int nextCoveredQueryTerms,
                int nextTotalQueryTerms
        ) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, windowCount, summary, tagsJson, metadataJson, sampleText,
                    score, recallSignals, nextMatchedFields, nextCoverageTerms, nextCoveredQueryTerms, nextTotalQueryTerms,
                    selectionReason, verifyAdmissionReason);
        }

        CandidateSource withSelectionReason(String nextSelectionReason) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, windowCount, summary, tagsJson, metadataJson, sampleText,
                    score, recallSignals, matchedFields, coverageTerms, coveredQueryTerms, totalQueryTerms,
                    nextSelectionReason, verifyAdmissionReason);
        }

        CandidateSource withVerifyAdmissionReason(String nextVerifyAdmissionReason) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, windowCount, summary, tagsJson, metadataJson, sampleText,
                    score, recallSignals, matchedFields, coverageTerms, coveredQueryTerms, totalQueryTerms,
                    selectionReason, nextVerifyAdmissionReason);
        }

        String metadataForScoring() {
            return String.join("\n", title, sourceType, summary, tagsJson, metadataJson, sampleText);
        }
    }

    public record ReadingWindow(
            String chunkId,
            String sourceId,
            String sourceSnapshotId,
            int chunkNo,
            String heading,
            String title,
            int windowNo,
            String content,
            String locationInfo,
            int score,
            String readRole,
            int anchorWindowNo,
            String readObjective
    ) {
        ReadingWindow withScore(int nextScore) {
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title, windowNo, content, locationInfo, nextScore, readRole, anchorWindowNo, readObjective);
        }

        ReadingWindow withReadRole(String nextReadRole) {
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title, windowNo, content, locationInfo, score, nextReadRole, anchorWindowNo, readObjective);
        }

        ReadingWindow withAnchorWindowNo(int nextAnchorWindowNo) {
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title, windowNo, content, locationInfo, score, readRole, nextAnchorWindowNo, readObjective);
        }

        ReadingWindow withReadObjective(String nextReadObjective) {
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title, windowNo, content, locationInfo, score, readRole, anchorWindowNo, nextReadObjective);
        }

        RetrievedChunk toRetrievedChunk() {
            return new RetrievedChunk(chunkId, sourceId, sourceSnapshotId, chunkNo, title, content, locationInfo, "SOURCE", 1, readRole == null || readRole.isBlank() ? "source-window" : readRole);
        }
    }

    public record NoteJournalHit(
            String noteId,
            String title,
            String summary,
            String content,
            int citationCount,
            int score,
            String freshnessStatus,
            String freshnessNote,
            int staleSourceCount,
            int unavailableSourceCount
    ) {
        NoteJournalHit withScore(int nextScore) {
            return new NoteJournalHit(noteId, title, summary, content, citationCount, nextScore,
                    freshnessStatus, freshnessNote, staleSourceCount, unavailableSourceCount);
        }

        NoteJournalHit withFreshness(
                String nextFreshnessStatus,
                String nextFreshnessNote,
                int nextStaleSourceCount,
                int nextUnavailableSourceCount
        ) {
            return new NoteJournalHit(noteId, title, summary, content, citationCount, score,
                    nextFreshnessStatus, nextFreshnessNote, nextStaleSourceCount, nextUnavailableSourceCount);
        }
    }

    private record NoteJournalSourceSignal(String noteId, String sourceId, String title, String summary, String content) {
    }

    private record NoteJournalFreshnessRow(String noteId, int staleSourceCount, int unavailableSourceCount) {
    }

    private record NoteJournalFreshness(
            String freshnessStatus,
            String freshnessNote,
            int staleSourceCount,
            int unavailableSourceCount,
            int penalty
    ) {
        private static NoteJournalFreshness fresh() {
            return new NoteJournalFreshness("fresh", "引用资料保持最新，可直接复用。", 0, 0, 0);
        }

        private static NoteJournalFreshness from(int staleSourceCount, int unavailableSourceCount) {
            if (unavailableSourceCount > 0) {
                return new NoteJournalFreshness(
                        "source-unavailable",
                        "这条历史 Note 绑定的部分来源已失效，当前只作为审计线索保留。",
                        staleSourceCount,
                        unavailableSourceCount,
                        6
                );
            }
            if (staleSourceCount > 0) {
                return new NoteJournalFreshness(
                        "stale-source-updated",
                        "这条历史 Note 绑定的来源在记录后已更新，需要重新核对原文窗口。",
                        staleSourceCount,
                        unavailableSourceCount,
                        3
                );
            }
            return fresh();
        }
    }

    private record NoteCitationPair(String noteId, String sourceId) {
    }

    private record MessageCitationPair(String messageId, String sourceId) {
    }

    private record SourceRelationRow(String sourceId, String title, String tagsJson) {
    }

    private record SourceStateSnapshot(String parseStatus, String indexStatus) {
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
            int score = sharedTagCount * 2 + coCitedNoteCount * 3 + coCitedTurnCount * 4 + titleOverlapScore + graphNeighborhoodScore;
            return new NoteRelationSignal(sourceId, sharedTagCount, coCitedNoteCount, coCitedTurnCount, titleOverlapScore, graphNeighborhoodScore, score);
        }
    }

    public record NoteRelationSignal(
            String sourceId,
            int sharedTagCount,
            int coCitedNoteCount,
            int coCitedTurnCount,
            int titleOverlapScore,
            int graphNeighborhoodScore,
            int score
    ) {
        static NoteRelationSignal empty(String sourceId) {
            return new NoteRelationSignal(sourceId, 0, 0, 0, 0, 0, 0);
        }
    }

    private record ScoredCandidateSource(CandidateSource source, int metadataScore, int noteScore, int relationScore, int readinessScore) {
        int score() {
            return source.score();
        }

        String sourceId() {
            return source.sourceId();
        }

        String selectionReason() {
            return source.selectionReason();
        }

        ScoredCandidateSource withSelectionReason(String selectionReason) {
            return new ScoredCandidateSource(source.withSelectionReason(selectionReason), metadataScore, noteScore, relationScore, readinessScore);
        }
    }

    private record MetadataRankResult(
            int score,
            List<String> matchedFields,
            List<String> coverageTerms,
            int coveredQueryTerms,
            int totalQueryTerms
    ) {
        private static MetadataRankResult empty() {
            return new MetadataRankResult(0, List.of(), List.of(), 0, 0);
        }
    }

    public record NoteRecallPlan(
            List<NoteJournalHit> journalHits,
            List<CandidateSource> candidateSources,
            List<CandidateSource> relationExpansionSources,
            List<CandidateSource> verifySources,
            NoteRecallTrace trace
    ) {
    }

    public record NoteRecallTrace(
            int journalHitCount,
            int candidateCount,
            int relationExpansionCount,
            int verifyBatchCount,
            int metadataScoreSum,
            int noteScoreSum,
            int relationScoreSum,
            int readinessScoreSum
    ) {
    }

    public record NoteEntryMetadata(
            String sourceId,
            String title,
            String sourceType,
            String summary,
            List<String> tags,
            List<String> metadataSignals,
            String parseStatus,
            String indexStatus,
            int chunkCount,
            int windowCount,
            boolean hasMoreWindows,
            List<WindowLocator> windowLocators,
            List<RelatedEntryPreview> relatedEntries
    ) {
    }

    public record WindowLocator(
            int chunkNo,
            String heading,
            int windowNo,
            String locationInfo,
            int score,
            String readRole,
            Integer anchorWindowNo,
            String readObjective
    ) {
    }

    public record RelatedEntryPreview(
            String sourceId,
            String title,
            int sharedTagCount,
            int coCitedNoteCount,
            int coCitedTurnCount,
            int lexicalOverlapScore,
            int graphNeighborhoodScore,
            String relationReason,
            int score
    ) {
    }
}
