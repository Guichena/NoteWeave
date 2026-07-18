package com.noteweave.workspace;

import com.noteweave.common.Ids;
import com.noteweave.common.BusinessException;
import com.noteweave.security.CurrentUserProvider;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceService implements WorkspaceQueryPort {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUserProvider;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final AuditActorProvider auditActorProvider;

    public WorkspaceService(
            JdbcTemplate jdbcTemplate,
            CurrentUserProvider currentUserProvider,
            WorkspaceAccessGuard workspaceAccessGuard,
            AuditActorProvider auditActorProvider
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserProvider = currentUserProvider;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.auditActorProvider = auditActorProvider;
    }

    @Transactional
    public WorkspaceResponse createWorkspace(CreateWorkspaceRequest request) {
        String userId = currentUserProvider.requireUserId();
        String actor = auditActorProvider.currentOrSystem("WORKSPACE");
        String workspaceId = Ids.newId();
        jdbcTemplate.update("""
                insert into workspace(id, owner_id, name, description, status, created_by, updated_by)
                values (?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, workspaceId, userId, request.name(), request.description(), actor, actor);
        jdbcTemplate.update("""
                insert into workspace_member(id, workspace_id, user_id, role, created_by, updated_by)
                values (?, ?, ?, 'OWNER', ?, ?)
                """, Ids.newId(), workspaceId, userId, actor, actor);
        return new WorkspaceResponse(workspaceId, request.name(), "ACTIVE", Instant.now());
    }

    public boolean exists(String workspaceId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from workspace where id = ?", Integer.class, workspaceId);
        return count != null && count > 0;
    }

    public boolean isWikiEnabled(String workspaceId) {
        workspaceAccessGuard.requireMember(workspaceId);
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
        workspaceAccessGuard.requireOwner(workspaceId);
        String actor = auditActorProvider.currentOrSystem("WORKSPACE");
        int updated = jdbcTemplate.update("""
                update workspace
                set wiki_enabled = ?, updated_by = ?, updated_at = current_timestamp
                where id = ?
                """, request.wikiEnabled(), actor, workspaceId);
        if (updated == 0) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
        return new WorkspaceWikiSettingsResponse(workspaceId, request.wikiEnabled());
    }

    public WorkspaceWikiSettingsResponse getWikiSettings(String workspaceId) {
        return new WorkspaceWikiSettingsResponse(workspaceId, isWikiEnabled(workspaceId));
    }

    public boolean isRetrievalStrategyV2Enabled(String workspaceId) {
        workspaceAccessGuard.requireMember(workspaceId);
        Boolean enabled = jdbcTemplate.query("""
                select retrieval_strategy_v2_enabled from workspace where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
            }
            return rs.getBoolean("retrieval_strategy_v2_enabled");
        }, workspaceId);
        return Boolean.TRUE.equals(enabled);
    }

    @Transactional
    public WorkspaceRetrievalSettingsResponse updateRetrievalSettings(
            String workspaceId,
            UpdateWorkspaceRetrievalSettingsRequest request
    ) {
        workspaceAccessGuard.requireOwner(workspaceId);
        String actor = auditActorProvider.currentOrSystem("WORKSPACE");
        boolean enabled = Boolean.TRUE.equals(request.retrievalStrategyV2Enabled());
        int updated = jdbcTemplate.update("""
                update workspace
                set retrieval_strategy_v2_enabled = ?, updated_by = ?, updated_at = current_timestamp
                where id = ?
                """, enabled, actor, workspaceId);
        if (updated == 0) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
        return new WorkspaceRetrievalSettingsResponse(
                workspaceId,
                enabled
        );
    }

    public WorkspaceRetrievalSettingsResponse getRetrievalSettings(String workspaceId) {
        return new WorkspaceRetrievalSettingsResponse(
                workspaceId,
                isRetrievalStrategyV2Enabled(workspaceId)
        );
    }
}
