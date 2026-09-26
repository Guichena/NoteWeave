package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.chat.NoteRetrievalService.RelatedEntryPreview;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns source-relation SQL and graph-based related-entry ranking for note metadata. */
final class NoteRelatedEntryService {

    private final JdbcTemplate jdbcTemplate;
    private final NoteRelationGraph noteRelationGraph;
    private final NoteRecallRepository recallRepository;

    NoteRelatedEntryService(
            JdbcTemplate jdbcTemplate,
            NoteRelationGraph noteRelationGraph,
            NoteRecallRepository recallRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.noteRelationGraph = noteRelationGraph;
        this.recallRepository = recallRepository;
    }

    Map<String, List<RelatedEntryPreview>> relatedEntriesForSources(
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
                        row.sourceId(), row.title(), "SOURCE", 0, 0,
                        row.generatedBy(), row.generatedRefId(), "", row.tagsJson(), "{}", "", 0, "",
                        List.of(), List.of(), 0, 0, "", ""
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
                relationCandidates, sourceIdsByNote, sourceIdsByAnsweredTurn);
        Map<String, Double> graphScores = noteRelationGraph.propagate(
                relationGraph, Set.of(anchor.sourceId()), 4, 0.2d);
        Map<String, Integer> coCitationCounts = new HashMap<>();
        Map<String, Integer> coCitedTurnCounts = noteRelationGraph.coOccurrence(
                Set.of(anchor.sourceId()), sourceIdsByAnsweredTurn);
        List<RelatedEntryPreview> related = coCitedRows.stream().map(row -> {
            String sourceId = row.relatedSourceId();
            int coCitedNotes = row.noteCount();
            coCitationCounts.put(sourceId, coCitedNotes);
            int sharedTags = sharedTagCounts.getOrDefault(sourceId, 0);
            int lexicalOverlap = lexicalOverlapScores.getOrDefault(sourceId, 0);
            int graphNeighborhoodScore = Math.max(
                    0, (int) Math.round(graphScores.getOrDefault(sourceId, 0.0d) * 24));
            int coCitedTurns = coCitedTurnCounts.getOrDefault(sourceId, 0);
            return relatedPreview(
                    sourceId, row.title(), row.generatedBy(), row.generatedRefId(), sharedTags,
                    coCitedNotes, coCitedTurns, lexicalOverlap, graphNeighborhoodScore);
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
                bySourceId.put(entry.getKey(), relatedPreview(
                        entry.getKey(), sourceRow.title(), sourceRow.generatedBy(), sourceRow.generatedRefId(),
                        entry.getValue(), coCitationCounts.getOrDefault(entry.getKey(), 0),
                        coCitedTurnCounts.getOrDefault(entry.getKey(), 0),
                        lexicalOverlapScores.getOrDefault(entry.getKey(), 0),
                        Math.max(0, (int) Math.round(
                                graphScores.getOrDefault(entry.getKey(), 0.0d) * 24))));
            }
        }
        return bySourceId.values().stream()
                .sorted(Comparator.comparingInt(RelatedEntryPreview::score).reversed()
                        .thenComparing(RelatedEntryPreview::title))
                .limit(3)
                .toList();
    }

    private RelatedEntryPreview relatedPreview(
            String sourceId,
            String title,
            String generatedBy,
            String generatedRefId,
            int sharedTags,
            int coCitedNotes,
            int coCitedTurns,
            int lexicalOverlap,
            int graphNeighborhoodScore
    ) {
        return new RelatedEntryPreview(
                sourceId, title, generatedBy, generatedRefId,
                sharedTags, coCitedNotes, coCitedTurns, lexicalOverlap,
                graphNeighborhoodScore,
                relatedReason(sharedTags, coCitedNotes, coCitedTurns, lexicalOverlap, graphNeighborhoodScore),
                sharedTags * 2 + coCitedNotes * 3 + coCitedTurns * 4
                        + lexicalOverlap + graphNeighborhoodScore);
    }

    private String relatedReason(
            int sharedTagCount,
            int coCitedNoteCount,
            int coCitedTurnCount,
            int lexicalOverlapScore,
            int graphNeighborhoodScore
    ) {
        List<String> reasons = new ArrayList<>();
        if (sharedTagCount > 0) reasons.add("shared-tags");
        if (coCitedNoteCount > 0) reasons.add("co-cited-notes");
        if (coCitedTurnCount > 0) reasons.add("co-cited-turns");
        if (lexicalOverlapScore > 0) reasons.add("title-summary-neighbor");
        if (graphNeighborhoodScore > 0) reasons.add("graph-neighbor");
        return reasons.isEmpty() ? "workspace-neighbor" : String.join(", ", reasons);
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
}
