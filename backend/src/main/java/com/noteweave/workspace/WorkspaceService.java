package com.noteweave.workspace;

import com.noteweave.common.Ids;
import com.noteweave.common.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceService {

    private static final String LOCAL_USER_ID = "local-user";

    private final JdbcTemplate jdbcTemplate;

    public WorkspaceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public WorkspaceResponse createWorkspace(CreateWorkspaceRequest request) {
        String workspaceId = Ids.newId();
        jdbcTemplate.update("""
                insert into workspace(id, owner_id, name, description, status)
                values (?, ?, ?, ?, 'ACTIVE')
                """, workspaceId, LOCAL_USER_ID, request.name(), request.description());
        jdbcTemplate.update("""
                insert into workspace_member(id, workspace_id, user_id, role)
                values (?, ?, ?, 'OWNER')
                """, Ids.newId(), workspaceId, LOCAL_USER_ID);
        return new WorkspaceResponse(workspaceId, request.name(), "ACTIVE", Instant.now());
    }

    public boolean exists(String workspaceId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from workspace where id = ?", Integer.class, workspaceId);
        return count != null && count > 0;
    }

    public boolean isWikiEnabled(String workspaceId) {
        Boolean enabled = jdbcTemplate.query("""
                select wiki_enabled from workspace where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
            }
            return rs.getBoolean("wiki_enabled");
        }, workspaceId);
        return Boolean.TRUE.equals(enabled);
    }

    @Transactional
    public WorkspaceWikiSettingsResponse updateWikiSettings(String workspaceId, UpdateWorkspaceWikiSettingsRequest request) {
        int updated = jdbcTemplate.update("""
                update workspace
                set wiki_enabled = ?, updated_at = current_timestamp
                where id = ?
                """, request.wikiEnabled(), workspaceId);
        if (updated == 0) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
        return new WorkspaceWikiSettingsResponse(workspaceId, request.wikiEnabled());
    }

    public WorkspaceWikiSettingsResponse getWikiSettings(String workspaceId) {
        return new WorkspaceWikiSettingsResponse(workspaceId, isWikiEnabled(workspaceId));
    }
}
