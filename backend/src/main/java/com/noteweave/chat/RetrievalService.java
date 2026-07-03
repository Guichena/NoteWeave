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
        Map<String, Integer> noteSignals = noteJournalSignals(workspaceId, terms);
        List<NoteJournalHit> journalHits = findNoteJournalHits(workspaceId, query);
        List<CandidateSource> candidates = jdbcTemplate.query("""
                select s.id, s.title, s.source_type, s.updated_at,
                       coalesce(s.summary, '') as summary,
                       coalesce(s.tags_json, '[]') as tags_json,
                       coalesce(s.metadata_json, '{}') as metadata_json,
                       count(c.id) as chunk_count,
                       coalesce(min(c.content), '') as sample_text
                from source s
                left join source_chunk c on c.source_id = s.id
                where s.workspace_id = ? and s.status = 'READY'
                group by s.id, s.title, s.source_type, s.updated_at, s.summary, s.tags_json, s.metadata_json
                order by s.updated_at desc
                limit 40
                """, (rs, rowNum) -> new CandidateSource(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("source_type"),
                rs.getInt("chunk_count"),
                rs.getString("summary"),
                rs.getString("tags_json"),
                rs.getString("metadata_json"),
                rs.getString("sample_text"),
                0,
                ""
        ), workspaceId);
        Map<String, Integer> metadataScores = new HashMap<>();
        for (CandidateSource source : candidates) {
            metadataScores.put(source.sourceId(), score(source.metadataForScoring(), terms));
        }
        Set<String> anchorSourceIds = new LinkedHashSet<>();
        candidates.stream()
                .filter(source -> metadataScores.getOrDefault(source.sourceId(), 0) > 0 || noteSignals.getOrDefault(source.sourceId(), 0) > 0)
                .sorted(Comparator.comparingInt((CandidateSource source) ->
                        metadataScores.getOrDefault(source.sourceId(), 0) + noteSignals.getOrDefault(source.sourceId(), 0) * 5).reversed())
                .limit(8)
                .forEach(source -> anchorSourceIds.add(source.sourceId()));
        Map<String, Integer> relationSignals = relationSignalsForNote(workspaceId, candidates, anchorSourceIds);
        List<ScoredCandidateSource> scored = candidates.stream()
                .map(source -> {
                    int metadataScore = metadataScores.getOrDefault(source.sourceId(), 0);
                    int noteScore = noteSignals.getOrDefault(source.sourceId(), 0) * 5;
                    int relationScore = relationSignals.getOrDefault(source.sourceId(), 0);
                    CandidateSource scoredSource = source.withScore(metadataScore + noteScore + relationScore)
                            .withRecallSignals(recallSignals(source, metadataScore, noteScore, relationScore));
                    return new ScoredCandidateSource(scoredSource, metadataScore, noteScore, relationScore);
                })
                .filter(source -> source.score() > 0 || terms.isEmpty())
                .sorted(Comparator.comparingInt(ScoredCandidateSource::score).reversed())
                .toList();
        if (scored.isEmpty()) {
            List<CandidateSource> fallback = candidates.stream().limit(4).toList();
            return new NoteRecallPlan(journalHits, fallback, List.of(), fallback, new NoteRecallTrace(
                    journalHits.size(), fallback.size(), 0, fallback.size(), 0, 0, 0
            ));
        }
        List<ScoredCandidateSource> candidateSources = selectCandidateSources(scored, 4);
        Set<String> selectedIds = new LinkedHashSet<>(candidateSources.stream().map(ScoredCandidateSource::sourceId).toList());
        List<ScoredCandidateSource> relationExpansion = scored.stream()
                .filter(source -> !selectedIds.contains(source.sourceId()) && source.relationScore() > 0)
                .sorted(Comparator.comparingInt(ScoredCandidateSource::relationScore).reversed()
                        .thenComparing(Comparator.comparingInt(ScoredCandidateSource::score).reversed()))
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
                        candidateSources.stream().mapToInt(ScoredCandidateSource::relationScore).sum()
                )
        );
    }

    private List<ScoredCandidateSource> selectCandidateSources(List<ScoredCandidateSource> scored, int limit) {
        if (scored.isEmpty()) {
            return List.of();
        }
        List<ScoredCandidateSource> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        takeByQuota(selected, seen, scored, limit, source -> source.noteScore() > 0, 1);
        takeByQuota(selected, seen, scored, limit, source -> source.metadataScore() > 0, 2);
        takeByQuota(selected, seen, scored, limit, source -> source.relationScore() > 0, 1);
        for (ScoredCandidateSource source : scored) {
            if (selected.size() >= limit) {
                break;
            }
            if (seen.add(source.sourceId())) {
                selected.add(source);
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
            int quota
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
            selected.add(source);
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
        for (ScoredCandidateSource source : candidates) {
            if (verify.size() >= limit) {
                break;
            }
            if (seen.add(source.sourceId())) {
                verify.add(source.source());
            }
        }
        for (ScoredCandidateSource source : expansions) {
            if (verify.size() >= limit) {
                break;
            }
            if (seen.add(source.sourceId())) {
                verify.add(source.source());
            }
        }
        return verify;
    }

    public List<ReadingWindow> openSourceWindowsForNote(String workspaceId, List<CandidateSource> sources, String query) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        Set<String> terms = extractTerms(query);
        List<ReadingWindow> windows = new ArrayList<>();
        for (CandidateSource source : sources) {
            windows.addAll(jdbcTemplate.query("""
                    select c.id, c.source_id, c.source_snapshot_id, c.chunk_no, s.title, w.content, w.location_info
                    from source_chunk c
                    join source s on s.id = c.source_id
                    join source_window w on w.source_chunk_id = c.id
                    where c.workspace_id = ? and c.source_id = ?
                    order by c.chunk_no asc, w.window_no asc
                    limit 12
                    """, (rs, rowNum) -> new ReadingWindow(
                    rs.getString("id"),
                    rs.getString("source_id"),
                    rs.getString("source_snapshot_id"),
                    rs.getInt("chunk_no"),
                    rs.getString("title"),
                    rs.getString("content"),
                    rs.getString("location_info"),
                    0
            ), workspaceId, source.sourceId()));
        }
        return windows.stream()
                .map(window -> window.withScore(score(window.content() + "\n" + window.title(), terms)))
                .sorted(Comparator.comparingInt(ReadingWindow::score).reversed())
                .limit(8)
                .toList();
    }

    public List<NoteEntryMetadata> readEntriesMetadataForNote(String workspaceId, List<CandidateSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        List<NoteEntryMetadata> metadataEntries = new ArrayList<>();
        for (CandidateSource source : sources) {
            Integer windowCount = jdbcTemplate.queryForObject("""
                    select count(*)
                    from source_window w
                    join source_chunk c on c.id = w.source_chunk_id
                    where c.workspace_id = ? and c.source_id = ?
                    """, Integer.class, workspaceId, source.sourceId());
            metadataEntries.add(new NoteEntryMetadata(
                    source.sourceId(),
                    source.title(),
                    source.sourceType(),
                    source.summary(),
                    parseJsonStringArray(source.tagsJson()),
                    metadataPreview(source.metadataJson()),
                    source.chunkCount(),
                    windowCount == null ? 0 : windowCount,
                    relatedEntriesForSource(workspaceId, source)
            ));
        }
        return metadataEntries;
    }

    public List<NoteJournalHit> findNoteJournalHits(String workspaceId, String query) {
        Set<String> terms = extractTerms(query);
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
                0
        ), workspaceId);
        return hits.stream()
                .map(hit -> hit.withScore(score(hit.title() + "\n" + hit.summary() + "\n" + hit.content(), terms)))
                .filter(hit -> hit.score() > 0 || terms.isEmpty())
                .sorted(Comparator.comparingInt(NoteJournalHit::score).reversed())
                .limit(3)
                .toList();
    }

    private Map<String, Integer> noteJournalSignals(String workspaceId, Set<String> terms) {
        if (terms.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> signals = new HashMap<>();
        List<NoteJournalSourceSignal> rows = jdbcTemplate.query("""
                select c.source_id, i.title, coalesce(v.summary, '') as summary, v.content
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                join citation c on c.id = kvc.citation_id
                where i.workspace_id = ? and i.item_type = 'NOTE' and i.status = 'ACTIVE'
                """, (rs, rowNum) -> new NoteJournalSourceSignal(
                rs.getString("source_id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("content")
        ), workspaceId);
        for (NoteJournalSourceSignal row : rows) {
            int rowScore = score(row.title() + "\n" + row.summary() + "\n" + row.content(), terms);
            if (rowScore > 0) {
                signals.merge(row.sourceId(), rowScore, Integer::sum);
            }
        }
        return signals;
    }

    private Map<String, Integer> relationSignalsForNote(String workspaceId, List<CandidateSource> sources, Set<String> anchorSourceIds) {
        if (sources.isEmpty() || anchorSourceIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> tagsBySource = new HashMap<>();
        for (CandidateSource source : sources) {
            tagsBySource.put(source.sourceId(), extractTags(source.tagsJson()));
        }
        Map<String, Integer> signals = new HashMap<>();
        for (CandidateSource source : sources) {
            if (anchorSourceIds.contains(source.sourceId())) {
                continue;
            }
            Set<String> tags = tagsBySource.getOrDefault(source.sourceId(), Set.of());
            int overlap = 0;
            for (String anchorId : anchorSourceIds) {
                Set<String> anchorTags = tagsBySource.getOrDefault(anchorId, Set.of());
                for (String tag : tags) {
                    if (anchorTags.contains(tag)) {
                        overlap++;
                    }
                }
            }
            if (overlap > 0) {
                signals.merge(source.sourceId(), overlap * 2, Integer::sum);
            }
        }
        Map<String, Integer> coCitation = noteCoCitationSignals(workspaceId, anchorSourceIds);
        coCitation.forEach((sourceId, value) -> signals.merge(sourceId, value * 3, Integer::sum));
        return signals;
    }

    private Map<String, Integer> noteCoCitationSignals(String workspaceId, Set<String> anchorSourceIds) {
        if (anchorSourceIds.isEmpty()) {
            return Map.of();
        }
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
        Map<String, Integer> signals = new HashMap<>();
        for (Set<String> sourceIds : sourceIdsByNote.values()) {
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

    private Set<String> extractTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return Set.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"([^\"]{1,80})\"").matcher(tagsJson.toLowerCase(Locale.ROOT));
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

    private List<RelatedEntryPreview> relatedEntriesForSource(String workspaceId, CandidateSource anchor) {
        Map<String, Integer> sharedTagCounts = new HashMap<>();
        Set<String> anchorTags = extractTags(anchor.tagsJson());
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
            }
        }

        Map<String, Integer> coCitationCounts = new HashMap<>();
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
                    return new RelatedEntryPreview(
                            sourceId,
                            rs.getString("title"),
                            sharedTags,
                            coCitedNotes,
                            sharedTags * 2 + coCitedNotes * 3
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
                        entry.getValue() * 2 + coCitationCounts.getOrDefault(entry.getKey(), 0) * 3
                ));
            }
        }
        return bySourceId.values().stream()
                .sorted(Comparator.comparingInt(RelatedEntryPreview::score).reversed()
                        .thenComparing(RelatedEntryPreview::title))
                .limit(3)
                .toList();
    }

    private String recallSignals(CandidateSource source, int metadataScore, int noteScore, int relationScore) {
        List<String> signals = new ArrayList<>();
        if (metadataScore > 0) {
            signals.add("metadata/tag/catalog");
        }
        if (noteScore > 0) {
            signals.add("journal-note");
        }
        if (relationScore > 0) {
            signals.add("relation-expansion");
        }
        if (!source.sampleText().isBlank()) {
            signals.add("source-window-ready");
        }
        if (signals.isEmpty()) {
            signals.add("workspace-recency");
        }
        return String.join(", ", signals);
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
            String summary,
            String tagsJson,
            String metadataJson,
            String sampleText,
            int score,
            String recallSignals
    ) {
        CandidateSource withScore(int nextScore) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, summary, tagsJson, metadataJson, sampleText, nextScore, recallSignals);
        }

        CandidateSource withRecallSignals(String nextRecallSignals) {
            return new CandidateSource(sourceId, title, sourceType, chunkCount, summary, tagsJson, metadataJson, sampleText, score, nextRecallSignals);
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
            String title,
            String content,
            String locationInfo,
            int score
    ) {
        ReadingWindow withScore(int nextScore) {
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, title, content, locationInfo, nextScore);
        }

        RetrievedChunk toRetrievedChunk() {
            return new RetrievedChunk(chunkId, sourceId, sourceSnapshotId, chunkNo, title, content, locationInfo, "SOURCE", 1, "source-window");
        }
    }

    public record NoteJournalHit(
            String noteId,
            String title,
            String summary,
            String content,
            int citationCount,
            int score
    ) {
        NoteJournalHit withScore(int nextScore) {
            return new NoteJournalHit(noteId, title, summary, content, citationCount, nextScore);
        }
    }

    private record NoteJournalSourceSignal(String sourceId, String title, String summary, String content) {
    }

    private record NoteCitationPair(String noteId, String sourceId) {
    }

    private record SourceRelationRow(String sourceId, String title, String tagsJson) {
    }

    private record ScoredCandidateSource(CandidateSource source, int metadataScore, int noteScore, int relationScore) {
        int score() {
            return source.score();
        }

        String sourceId() {
            return source.sourceId();
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
            int relationScoreSum
    ) {
    }

    public record NoteEntryMetadata(
            String sourceId,
            String title,
            String sourceType,
            String summary,
            List<String> tags,
            List<String> metadataSignals,
            int chunkCount,
            int windowCount,
            List<RelatedEntryPreview> relatedEntries
    ) {
    }

    public record RelatedEntryPreview(
            String sourceId,
            String title,
            int sharedTagCount,
            int coCitedNoteCount,
            int score
    ) {
    }
}
