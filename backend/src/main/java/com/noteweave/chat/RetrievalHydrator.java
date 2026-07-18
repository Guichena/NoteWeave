package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RetrievalHydrator {

    private final JdbcTemplate jdbcTemplate;

    public RetrievalHydrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, PassageOwnership> hydratePassageOwnership(
            String workspaceId,
            List<String> chunkIds
    ) {
        List<String> ids = distinctIds(chunkIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Object> parameters = parameters(workspaceId, ids);
        List<PassageOwnership> rows = jdbcTemplate.query("""
                select c.id as chunk_id, c.source_id, c.source_snapshot_id,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id
                from source_chunk c
                join source s
                  on s.id = c.source_id and s.workspace_id = c.workspace_id
                join source_snapshot ss
                  on ss.id = c.source_snapshot_id and ss.source_id = c.source_id
                where c.workspace_id = ? and c.id in (%s)
                  and s.status = 'READY'
                  and s.index_status = 'INDEXED'
                  and ss.index_status = 'INDEXED'
                  and ss.version_no = (
                      select max(current_ss.version_no)
                      from source_snapshot current_ss
                      where current_ss.source_id = s.id
                        and current_ss.index_status = 'INDEXED'
                  )
                  and c.projection_status = 'PROJECTED'
                """.formatted(placeholders(ids.size())), (rs, rowNum) -> new PassageOwnership(
                rs.getString("chunk_id"),
                rs.getString("source_id"),
                rs.getString("source_snapshot_id"),
                rs.getString("generated_by"),
                rs.getString("generated_ref_id")
        ), parameters.toArray());
        Map<String, PassageOwnership> byChunkId = new LinkedHashMap<>();
        rows.forEach(row -> byChunkId.put(row.chunkId(), row));
        return Map.copyOf(byChunkId);
    }

    public Map<String, List<ReadingWindow>> hydrateNoteWindows(
            String workspaceId,
            List<String> sourceIds
    ) {
        List<String> ids = distinctIds(sourceIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Object> parameters = parameters(workspaceId, ids);
        List<ReadingWindow> rows = jdbcTemplate.query("""
                select c.id, c.source_id, c.source_snapshot_id, c.chunk_no,
                       coalesce(c.heading, '') as heading,
                       s.title,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id,
                       w.window_no, w.content, w.location_info
                from source_chunk c
                join source s
                  on s.id = c.source_id and s.workspace_id = c.workspace_id
                join source_snapshot ss
                  on ss.id = c.source_snapshot_id and ss.source_id = c.source_id
                join source_window w on w.source_chunk_id = c.id
                where c.workspace_id = ? and c.source_id in (%s)
                  and s.status = 'READY'
                  and s.index_status = 'INDEXED'
                  and ss.index_status = 'INDEXED'
                  and ss.version_no = (
                      select max(current_ss.version_no)
                      from source_snapshot current_ss
                      where current_ss.source_id = s.id
                        and current_ss.index_status = 'INDEXED'
                  )
                  and c.projection_status = 'PROJECTED'
                order by c.source_id asc, c.chunk_no asc, w.window_no asc
                """.formatted(placeholders(ids.size())), (rs, rowNum) -> new ReadingWindow(
                rs.getString("id"),
                rs.getString("source_id"),
                rs.getString("source_snapshot_id"),
                rs.getInt("chunk_no"),
                rs.getString("heading"),
                rs.getString("title"),
                rs.getString("generated_by"),
                rs.getString("generated_ref_id"),
                rs.getInt("window_no"),
                rs.getString("content"),
                rs.getString("location_info"),
                0,
                "candidate-window",
                rs.getInt("window_no"),
                "candidate-pool"
        ), parameters.toArray());
        Map<String, List<ReadingWindow>> bySourceId = new LinkedHashMap<>();
        for (ReadingWindow row : rows) {
            List<ReadingWindow> sourceWindows = bySourceId.computeIfAbsent(
                    row.sourceId(), ignored -> new ArrayList<>());
            if (sourceWindows.size() < 16) {
                sourceWindows.add(row);
            }
        }
        Map<String, List<ReadingWindow>> immutable = new LinkedHashMap<>();
        bySourceId.forEach((sourceId, windows) -> immutable.put(sourceId, List.copyOf(windows)));
        return Map.copyOf(immutable);
    }

    public Map<String, NoteSourceStats> hydrateNoteSourceStats(
            String workspaceId,
            List<String> sourceIds
    ) {
        List<String> ids = distinctIds(sourceIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Object> parameters = parameters(workspaceId, ids);
        List<NoteSourceStats> rows = jdbcTemplate.query("""
                select s.id,
                       coalesce(s.parse_status, 'UNKNOWN') as parse_status,
                       coalesce(s.index_status, 'UNKNOWN') as index_status,
                       count(w.id) as window_count
                from source s
                left join source_snapshot ss
                  on ss.source_id = s.id
                 and ss.index_status = 'INDEXED'
                 and ss.version_no = (
                     select max(current_ss.version_no)
                     from source_snapshot current_ss
                     where current_ss.source_id = s.id
                       and current_ss.index_status = 'INDEXED'
                 )
                left join source_chunk c
                  on c.source_id = s.id and c.workspace_id = s.workspace_id
                 and c.source_snapshot_id = ss.id
                 and c.projection_status = 'PROJECTED'
                left join source_window w on w.source_chunk_id = c.id
                where s.workspace_id = ? and s.id in (%s)
                group by s.id, s.parse_status, s.index_status
                """.formatted(placeholders(ids.size())), (rs, rowNum) -> new NoteSourceStats(
                rs.getString("id"),
                rs.getString("parse_status"),
                rs.getString("index_status"),
                rs.getInt("window_count")
        ), parameters.toArray());
        Map<String, NoteSourceStats> bySourceId = new LinkedHashMap<>();
        rows.forEach(row -> bySourceId.put(row.sourceId(), row));
        return Map.copyOf(bySourceId);
    }

    private List<String> distinctIds(List<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        sourceIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .forEach(ids::add);
        return List.copyOf(ids);
    }

    private List<Object> parameters(String workspaceId, List<String> ids) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(ids);
        return parameters;
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    public record PassageOwnership(
            String chunkId,
            String sourceId,
            String sourceSnapshotId,
            String generatedBy,
            String generatedRefId
    ) {
    }

    public record NoteSourceStats(
            String sourceId,
            String parseStatus,
            String indexStatus,
            int windowCount
    ) {
        public static NoteSourceStats unknown(String sourceId) {
            return new NoteSourceStats(sourceId, "UNKNOWN", "UNKNOWN", 0);
        }
    }
}
