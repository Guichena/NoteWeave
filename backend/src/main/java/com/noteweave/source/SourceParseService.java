package com.noteweave.source;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceParseService {

    private final JdbcTemplate jdbcTemplate;
    private final DocumentChunker documentChunker;

    public SourceParseService(JdbcTemplate jdbcTemplate, DocumentChunker documentChunker) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentChunker = documentChunker;
    }

    @Transactional
    public void parseAndIndex(String workspaceId, String sourceId, String snapshotId, byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<String> chunks = documentChunker.chunk(text);
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = com.noteweave.common.Ids.newId();
            String content = chunks.get(i);
            jdbcTemplate.update("""
                    insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no, heading, content, token_estimate, location_info)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, chunkId, workspaceId, sourceId, snapshotId, i, "片段 " + (i + 1), content,
                    Math.max(1, content.length() / 2), "chunk:" + i);
            jdbcTemplate.update("""
                    insert into source_window(id, source_chunk_id, window_no, content, location_info)
                    values (?, ?, ?, ?, ?)
                    """, com.noteweave.common.Ids.newId(), chunkId, 0, content, "chunk:" + i);
        }
        jdbcTemplate.update("update source set parse_status = 'PARSED', index_status = 'INDEXED', status = 'READY', updated_at = current_timestamp where id = ?", sourceId);
        jdbcTemplate.update("update source_snapshot set parse_status = 'PARSED', index_status = 'INDEXED' where id = ?", snapshotId);
    }
}
