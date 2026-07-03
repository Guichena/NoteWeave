package com.noteweave.chat;

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

    public RetrievalService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RetrievedChunk> retrieveForQa(String workspaceId, String query) {
        List<RetrievedChunk> candidates = jdbcTemplate.query("""
                select c.id, c.source_id, c.source_snapshot_id, c.chunk_no, c.content, c.location_info, s.title
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
                0
        ), workspaceId);
        Set<String> terms = extractTerms(query);
        List<RetrievedChunk> scored = candidates.stream()
                .map(chunk -> chunk.withScore(score(chunk.content(), terms)))
                .filter(chunk -> chunk.score() > 0 || terms.isEmpty())
                .sorted(Comparator.comparingInt(RetrievedChunk::score).reversed())
                .limit(4)
                .toList();
        if (!scored.isEmpty()) {
            return scored;
        }
        return candidates.stream().limit(4).toList();
    }

    public List<CandidateSource> findCandidateSourcesForNote(String workspaceId, String query) {
        Set<String> terms = extractTerms(query);
        Map<String, Integer> noteSignals = noteJournalSignals(workspaceId, terms);
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
        List<CandidateSource> scored = candidates.stream()
                .map(source -> {
                    int metadataScore = score(source.metadataForScoring(), terms);
                    int noteScore = noteSignals.getOrDefault(source.sourceId(), 0) * 5;
                    return source.withScore(metadataScore + noteScore)
                            .withRecallSignals(recallSignals(source, metadataScore, noteScore));
                })
                .filter(source -> source.score() > 0 || terms.isEmpty())
                .sorted(Comparator.comparingInt(CandidateSource::score).reversed())
                .limit(4)
                .toList();
        if (!scored.isEmpty()) {
            return scored;
        }
        return candidates.stream().limit(4).toList();
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

    private String recallSignals(CandidateSource source, int metadataScore, int noteScore) {
        List<String> signals = new ArrayList<>();
        if (metadataScore > 0) {
            signals.add("metadata/tag/catalog");
        }
        if (noteScore > 0) {
            signals.add("journal-note");
        }
        if (!source.sampleText().isBlank()) {
            signals.add("source-window-ready");
        }
        if (signals.isEmpty()) {
            signals.add("workspace-recency");
        }
        return String.join(", ", signals);
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
            int score
    ) {
        RetrievedChunk withScore(int nextScore) {
            return new RetrievedChunk(chunkId, sourceId, sourceSnapshotId, chunkNo, title, content, locationInfo, nextScore);
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
            return new RetrievedChunk(chunkId, sourceId, sourceSnapshotId, chunkNo, title, content, locationInfo, 1);
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
}
