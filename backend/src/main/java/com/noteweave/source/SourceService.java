package com.noteweave.source;

import com.noteweave.common.BusinessException;
import com.noteweave.knowledge.WikiIngestService;
import com.noteweave.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceService {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceService workspaceService;
    private final WikiIngestService wikiIngestService;

    public SourceService(JdbcTemplate jdbcTemplate, WorkspaceService workspaceService, WikiIngestService wikiIngestService) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceService = workspaceService;
        this.wikiIngestService = wikiIngestService;
    }

    public List<SourceResponse> listSources(String workspaceId) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
        return jdbcTemplate.query("""
                select id, title, source_type, status, parse_status, index_status, updated_at
                from source
                where workspace_id = ? and status <> 'DELETED'
                order by updated_at desc, id desc
                """, (rs, rowNum) -> new SourceResponse(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("source_type"),
                rs.getString("status"),
                rs.getString("parse_status"),
                rs.getString("index_status"),
                toInstant(rs.getTimestamp("updated_at"))
        ), workspaceId);
    }

    @Transactional
    public DeleteSourceResponse deleteSource(String workspaceId, String sourceId) {
        SourceRef source = loadSource(workspaceId, sourceId);
        jdbcTemplate.update("""
                update source
                set status = 'DELETED', parse_status = 'DELETED', index_status = 'DELETED', updated_at = current_timestamp
                where workspace_id = ? and id = ?
                """, workspaceId, sourceId);
        String wikiTaskId = wikiIngestService.enqueueAndRunSourceRetractIfEnabled(workspaceId, sourceId, source.title());
        return new DeleteSourceResponse(sourceId, "DELETED", wikiTaskId);
    }

    private SourceRef loadSource(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select id, title, status
                from source
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("SOURCE_NOT_FOUND", "资料不存在");
            }
            if ("DELETED".equals(rs.getString("status"))) {
                throw new BusinessException("SOURCE_ALREADY_DELETED", "资料已经删除");
            }
            return new SourceRef(rs.getString("id"), rs.getString("title"));
        }, workspaceId, sourceId);
    }

    private Instant toInstant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }

    private record SourceRef(String sourceId, String title) {
    }
}
