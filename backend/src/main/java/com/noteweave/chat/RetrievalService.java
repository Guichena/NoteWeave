package com.noteweave.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        String lower = content.toLowerCase(Locale.ROOT);
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
}
