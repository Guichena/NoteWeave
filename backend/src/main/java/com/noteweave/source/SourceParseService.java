package com.noteweave.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceParseService {

    private static final int WINDOW_CHARS = 320;
    private static final int WINDOW_OVERLAP = 80;

    private final JdbcTemplate jdbcTemplate;
    private final DocumentChunker documentChunker;
    private final ObjectMapper objectMapper;

    public SourceParseService(JdbcTemplate jdbcTemplate, DocumentChunker documentChunker, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentChunker = documentChunker;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void parseAndIndex(String workspaceId, String sourceId, String snapshotId, byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        SourceMeta sourceMeta = loadSourceMeta(sourceId);
        List<String> chunks = documentChunker.chunk(text);
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = Ids.newId();
            String content = chunks.get(i);
            jdbcTemplate.update("""
                    insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no, heading, content, token_estimate, location_info)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, chunkId, workspaceId, sourceId, snapshotId, i, "片段 " + (i + 1), content,
                    Math.max(1, content.length() / 2), "chunk:" + i);
            List<String> windows = buildReadWindows(content);
            for (int windowNo = 0; windowNo < windows.size(); windowNo++) {
                jdbcTemplate.update("""
                        insert into source_window(id, source_chunk_id, window_no, content, location_info)
                        values (?, ?, ?, ?, ?)
                        """, Ids.newId(), chunkId, windowNo, windows.get(windowNo), "chunk:" + i + "/window:" + windowNo);
            }
        }

        List<String> tags = deriveTags(sourceMeta.title(), sourceMeta.sourceType(), text);
        Map<String, Object> metadata = Map.of(
                "title", sourceMeta.title(),
                "source_type", sourceMeta.sourceType(),
                "chunk_count", chunks.size(),
                "char_count", text.length(),
                "entry_strategy", "marginalia_structured_reading_funnel"
        );
        jdbcTemplate.update("""
                update source
                set summary = ?, tags_json = ?, metadata_json = ?,
                    parse_status = 'PARSED', index_status = 'INDEXED', status = 'READY', updated_at = current_timestamp
                where id = ?
                """, summarize(text), writeJson(tags), writeJson(metadata), sourceId);
        jdbcTemplate.update("update source_snapshot set parse_status = 'PARSED', index_status = 'INDEXED' where id = ?", snapshotId);
    }

    private SourceMeta loadSourceMeta(String sourceId) {
        return jdbcTemplate.query("""
                select title, source_type from source where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("SOURCE_NOT_FOUND", "资料不存在");
            }
            return new SourceMeta(rs.getString("title"), rs.getString("source_type"));
        }, sourceId);
    }

    private String summarize(String text) {
        String normalized = text == null ? "" : text.replace("\r", "").replace("\n", " ").trim();
        if (normalized.length() <= 360) {
            return normalized;
        }
        return normalized.substring(0, 359) + "...";
    }

    private List<String> deriveTags(String title, String sourceType, String text) {
        Set<String> tags = new LinkedHashSet<>();
        if (sourceType != null && !sourceType.isBlank()) {
            tags.add(sourceType.toLowerCase(Locale.ROOT));
        }
        collectTerms(tags, title);
        collectTerms(tags, text == null ? "" : text.substring(0, Math.min(text.length(), 800)));
        return new ArrayList<>(tags).stream().limit(12).toList();
    }

    private void collectTerms(Set<String> tags, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String[] parts = value.toLowerCase(Locale.ROOT).split("[^\\p{IsHan}a-zA-Z0-9]+");
        for (String part : parts) {
            if (part.length() >= 2) {
                tags.add(part);
            }
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("JSON_WRITE_FAILED", "资料元数据序列化失败");
        }
    }

    private List<String> buildReadWindows(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank()) {
            return List.of("");
        }
        if (normalized.length() <= WINDOW_CHARS) {
            return List.of(normalized);
        }
        List<String> windows = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + WINDOW_CHARS);
            if (end < normalized.length()) {
                int paragraphBreak = normalized.lastIndexOf("\n\n", end);
                if (paragraphBreak > start + WINDOW_CHARS / 2) {
                    end = paragraphBreak;
                }
            }
            windows.add(normalized.substring(start, end).trim());
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - WINDOW_OVERLAP, start + 1);
        }
        return windows;
    }

    private record SourceMeta(String title, String sourceType) {
    }
}
