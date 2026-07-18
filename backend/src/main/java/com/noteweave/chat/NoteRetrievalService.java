package com.noteweave.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.RetrievalHydrator.NoteSourceStats;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
public class NoteRetrievalService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RetrievalHydrator retrievalHydrator;
    private final NoteReadingPlanner readingPlanner;
    private final NoteRelationGraph noteRelationGraph;
    private final NoteRecallRepository recallRepository;

    public NoteRetrievalService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RetrievalHydrator retrievalHydrator,
            NoteReadingPlanner readingPlanner,
            NoteRelationGraph noteRelationGraph,
            NoteRecallRepository recallRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.retrievalHydrator = retrievalHydrator;
        this.readingPlanner = readingPlanner;
        this.noteRelationGraph = noteRelationGraph;
        this.recallRepository = recallRepository;
    }

    public List<NoteEntryMetadata> readEntriesMetadataForNote(String workspaceId, List<CandidateSource> sources, String query) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        List<String> sourceIds = sources.stream().map(CandidateSource::sourceId).toList();
        Map<String, List<ReadingWindow>> windowsBySourceId = retrievalHydrator.hydrateNoteWindows(
                workspaceId, sourceIds);
        Map<String, NoteSourceStats> statsBySourceId = retrievalHydrator.hydrateNoteSourceStats(
                workspaceId, sourceIds);
        Map<String, List<RelatedEntryPreview>> relatedEntriesBySourceId =
                relatedEntriesForSources(workspaceId, sources);
        List<NoteEntryMetadata> metadataEntries = new ArrayList<>();
        for (CandidateSource source : sources) {
            NoteSourceStats stats = statsBySourceId.getOrDefault(
                    source.sourceId(), NoteSourceStats.unknown(source.sourceId()));
            List<WindowLocator> windowLocators = readingPlanner.locators(
                    windowsBySourceId.getOrDefault(source.sourceId(), List.of()), query);
            int totalWindowCount = stats.windowCount();
            metadataEntries.add(new NoteEntryMetadata(
                    source.sourceId(),
                    source.title(),
                    source.sourceType(),
                    source.generatedBy(),
                    source.generatedRefId(),
                    source.summary(),
                    parseJsonStringArray(source.tagsJson()),
                    metadataPreview(source.metadataJson()),
                    stats.parseStatus(),
                    stats.indexStatus(),
                    source.chunkCount(),
                    totalWindowCount,
                    totalWindowCount > windowLocators.size(),
                    windowLocators,
                    relatedEntriesBySourceId.getOrDefault(source.sourceId(), List.of())
            ));
        }
        return metadataEntries;
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

    private Map<String, List<RelatedEntryPreview>> relatedEntriesForSources(
            String workspaceId,
            List<CandidateSource> anchors
    ) {
        if (anchors == null || anchors.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> sourceIdsByAnsweredTurn = recallRepository.sourceIdsByAnsweredTurn(workspaceId);
        Map<String, Set<String>> sourceIdsByNote = recallRepository.sourceIdsByNote(workspaceId);
        List<SourceRelationRow> relationRows = jdbcTemplate.query("""
                select s.id, s.title, coalesce(s.tags_json, '[]') as tags_json,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id
                from source s
                where s.workspace_id = ? and s.status = 'READY'
                order by s.updated_at desc
                limit 30
                """, (rs, rowNum) -> new SourceRelationRow(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("tags_json"),
                rs.getString("generated_by"),
                rs.getString("generated_ref_id")
        ), workspaceId);
        List<String> anchorSourceIds = anchors.stream()
                .map(CandidateSource::sourceId)
                .distinct()
                .toList();
        List<Object> coCitationArguments = new ArrayList<>();
        coCitationArguments.add(workspaceId);
        coCitationArguments.addAll(anchorSourceIds);
        List<CoCitedSourcePair> coCitedRows = jdbcTemplate.query("""
                select c1.source_id as anchor_source_id,
                       c2.source_id as related_source_id,
                       s.title,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id,
                       count(distinct i.id) as note_count
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
                  and c1.source_id in (%s)
                  and c1.source_id <> c2.source_id
                group by c1.source_id, c2.source_id, s.title, s.generated_by, s.generated_ref_id
                order by c1.source_id, note_count desc, s.title asc
                """.formatted(String.join(",", java.util.Collections.nCopies(anchorSourceIds.size(), "?"))),
                (rs, rowNum) -> new CoCitedSourcePair(
                rs.getString("anchor_source_id"),
                rs.getString("related_source_id"),
                rs.getString("title"),
                rs.getString("generated_by"),
                rs.getString("generated_ref_id"),
                rs.getInt("note_count")
        ), coCitationArguments.toArray());
        Map<String, List<CoCitedSourcePair>> coCitedByAnchor = new HashMap<>();
        coCitedRows.forEach(row -> coCitedByAnchor
                .computeIfAbsent(row.anchorSourceId(), ignored -> new ArrayList<>()).add(row));
        Map<String, List<RelatedEntryPreview>> result = new LinkedHashMap<>();
        for (CandidateSource anchor : anchors) {
            result.put(anchor.sourceId(), relatedEntriesForSource(
                    anchor, relationRows, coCitedByAnchor.getOrDefault(anchor.sourceId(), List.of()),
                    sourceIdsByNote, sourceIdsByAnsweredTurn));
        }
        return Map.copyOf(result);
    }

    private List<RelatedEntryPreview> relatedEntriesForSource(
            CandidateSource anchor,
            List<SourceRelationRow> relationRows,
            List<CoCitedSourcePair> coCitedRows,
            Map<String, Set<String>> sourceIdsByNote,
            Map<String, Set<String>> sourceIdsByAnsweredTurn
    ) {
        Map<String, Integer> sharedTagCounts = new HashMap<>();
        Map<String, Integer> lexicalOverlapScores = new HashMap<>();
        Set<String> anchorTags = noteRelationGraph.tags(anchor.tagsJson());
        List<CandidateSource> relationCandidates = new ArrayList<>();
        relationCandidates.add(anchor);
        if (!anchorTags.isEmpty()) {
            for (SourceRelationRow row : relationRows.stream()
                    .filter(row -> !anchor.sourceId().equals(row.sourceId()))
                    .limit(30)
                    .toList()) {
                relationCandidates.add(new CandidateSource(
                        row.sourceId(),
                        row.title(),
                        "SOURCE",
                        0,
                        0,
                        row.generatedBy(),
                        row.generatedRefId(),
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
                Set<String> otherTags = noteRelationGraph.tags(row.tagsJson());
                int overlap = 0;
                for (String tag : anchorTags) {
                    if (otherTags.contains(tag)) {
                        overlap++;
                    }
                }
                if (overlap > 0) {
                    sharedTagCounts.put(row.sourceId(), overlap);
                }
                int lexicalOverlap = noteRelationGraph.overlapTerms(
                        anchor.title() + "\n" + anchor.summary(), row.title());
                if (lexicalOverlap > 0) {
                    lexicalOverlapScores.put(row.sourceId(), lexicalOverlap);
                }
            }
        }

        Map<String, Map<String, Integer>> relationGraph = noteRelationGraph.build(
                relationCandidates,
                sourceIdsByNote,
                sourceIdsByAnsweredTurn
        );
        Map<String, Double> graphScores = noteRelationGraph.propagate(relationGraph, Set.of(anchor.sourceId()), 4, 0.2d);
        Map<String, Integer> coCitationCounts = new HashMap<>();
        Map<String, Integer> coCitedTurnCounts = noteRelationGraph.coOccurrence(Set.of(anchor.sourceId()), sourceIdsByAnsweredTurn);
        List<RelatedEntryPreview> related = coCitedRows.stream().map(row -> {
                    String sourceId = row.relatedSourceId();
                    int coCitedNotes = row.noteCount();
                    coCitationCounts.put(sourceId, coCitedNotes);
                    int sharedTags = sharedTagCounts.getOrDefault(sourceId, 0);
                    int lexicalOverlap = lexicalOverlapScores.getOrDefault(sourceId, 0);
                    int graphNeighborhoodScore = Math.max(0, (int) Math.round(graphScores.getOrDefault(sourceId, 0.0d) * 24));
                    int coCitedTurns = coCitedTurnCounts.getOrDefault(sourceId, 0);
                    return new RelatedEntryPreview(
                            sourceId,
                            row.title(),
                            row.generatedBy(),
                            row.generatedRefId(),
                            sharedTags,
                            coCitedNotes,
                            coCitedTurns,
                            lexicalOverlap,
                            graphNeighborhoodScore,
                            relatedReason(sharedTags, coCitedNotes, coCitedTurns, lexicalOverlap, graphNeighborhoodScore),
                            sharedTags * 2 + coCitedNotes * 3 + coCitedTurns * 4 + lexicalOverlap + graphNeighborhoodScore
                    );
                }).toList();

        Map<String, RelatedEntryPreview> bySourceId = new HashMap<>();
        for (RelatedEntryPreview item : related) {
            bySourceId.put(item.sourceId(), item);
        }
        for (Map.Entry<String, Integer> entry : sharedTagCounts.entrySet()) {
            if (!bySourceId.containsKey(entry.getKey())) {
                SourceRelationRow sourceRow = relationRows.stream()
                        .filter(row -> row.sourceId().equals(entry.getKey()))
                        .findFirst()
                        .orElse(new SourceRelationRow(entry.getKey(), entry.getKey(), "[]", "", ""));
                bySourceId.put(entry.getKey(), new RelatedEntryPreview(
                        entry.getKey(),
                        sourceRow.title(),
                        sourceRow.generatedBy(),
                        sourceRow.generatedRefId(),
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

    public record CandidateSource(
            String sourceId,
            String title,
            String sourceType,
            int chunkCount,
            int windowCount,
            String generatedBy,
            String generatedRefId,
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
            return new CandidateSource(sourceId, title, sourceType, chunkCount, windowCount, generatedBy, generatedRefId, summary, tagsJson, metadataJson, sampleText,
                    nextScore, recallSignals, matchedFields, coverageTerms, coveredQueryTerms, totalQueryTerms,
                    selectionReason, verifyAdmissionReason);
        }

        CandidateSource withRecallSignals(String nextRecallSignals) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, windowCount, generatedBy, generatedRefId, summary, tagsJson, metadataJson, sampleText,
                    score, nextRecallSignals, matchedFields, coverageTerms, coveredQueryTerms, totalQueryTerms,
                    selectionReason, verifyAdmissionReason);
        }

        CandidateSource withMetadataRank(
                List<String> nextMatchedFields,
                List<String> nextCoverageTerms,
                int nextCoveredQueryTerms,
                int nextTotalQueryTerms
        ) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, windowCount, generatedBy, generatedRefId, summary, tagsJson, metadataJson, sampleText,
                    score, recallSignals, nextMatchedFields, nextCoverageTerms, nextCoveredQueryTerms, nextTotalQueryTerms,
                    selectionReason, verifyAdmissionReason);
        }

        CandidateSource withSelectionReason(String nextSelectionReason) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, windowCount, generatedBy, generatedRefId, summary, tagsJson, metadataJson, sampleText,
                    score, recallSignals, matchedFields, coverageTerms, coveredQueryTerms, totalQueryTerms,
                    nextSelectionReason, verifyAdmissionReason);
        }

        CandidateSource withVerifyAdmissionReason(String nextVerifyAdmissionReason) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, windowCount, generatedBy, generatedRefId, summary, tagsJson, metadataJson, sampleText,
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
            String generatedBy,
            String generatedRefId,
            int windowNo,
            String content,
            String locationInfo,
            int score,
            String readRole,
            int anchorWindowNo,
            String readObjective
    ) {
        ReadingWindow withScore(int nextScore) {
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title, generatedBy, generatedRefId, windowNo, content, locationInfo, nextScore, readRole, anchorWindowNo, readObjective);
        }

        ReadingWindow withReadRole(String nextReadRole) {
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title, generatedBy, generatedRefId, windowNo, content, locationInfo, score, nextReadRole, anchorWindowNo, readObjective);
        }

        ReadingWindow withAnchorWindowNo(int nextAnchorWindowNo) {
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title, generatedBy, generatedRefId, windowNo, content, locationInfo, score, readRole, nextAnchorWindowNo, readObjective);
        }

        ReadingWindow withReadObjective(String nextReadObjective) {
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title, generatedBy, generatedRefId, windowNo, content, locationInfo, score, readRole, anchorWindowNo, nextReadObjective);
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

    private record SourceRelationRow(
            String sourceId,
            String title,
            String tagsJson,
            String generatedBy,
            String generatedRefId
    ) {
    }

    private record CoCitedSourcePair(
            String anchorSourceId,
            String relatedSourceId,
            String title,
            String generatedBy,
            String generatedRefId,
            int noteCount
    ) {
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
            String generatedBy,
            String generatedRefId,
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
            String generatedBy,
            String generatedRefId,
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
