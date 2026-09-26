package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workspace opt-in for shadow compilation; ACTIVE is deliberately unavailable. */
@Service
public class ContextV2RolloutService {
    private final JdbcTemplate jdbc;
    private final WorkspaceAccessGuard access;
    private final boolean globalShadowEnabled;

    public ContextV2RolloutService(JdbcTemplate jdbc, WorkspaceAccessGuard access,
                                   @Value("${noteweave.context.v2.shadow-enabled:false}")
                                   boolean globalShadowEnabled) {
        this.jdbc = jdbc;
        this.access = access;
        this.globalShadowEnabled = globalShadowEnabled;
    }

    public RolloutState get(String workspaceId) {
        access.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_ADMIN);
        String configured = configuredMode(workspaceId);
        return new RolloutState(configured, globalShadowEnabled && "SHADOW".equals(configured));
    }

    public boolean shadowEnabled(String workspaceId) {
        return globalShadowEnabled && "SHADOW".equals(configuredMode(workspaceId));
    }

    @Transactional
    public RolloutState set(String workspaceId, String mode) {
        access.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_ADMIN);
        if (!"OFF".equals(mode) && !"SHADOW".equals(mode)) {
            throw new BusinessException("CONTEXT_V2_ROLLOUT_MODE_INVALID",
                    "Only OFF and SHADOW modes are available", HttpStatus.BAD_REQUEST);
        }
        int updated = jdbc.update("""
                update context_v2_workspace_rollout
                set mode = ?, lock_version = lock_version + 1, updated_at = current_timestamp
                where workspace_id = ?
                """, mode, workspaceId);
        if (updated == 0) {
            try {
                jdbc.update("""
                        insert into context_v2_workspace_rollout(workspace_id, mode) values (?, ?)
                        """, workspaceId, mode);
            } catch (DuplicateKeyException conflict) {
                jdbc.update("""
                        update context_v2_workspace_rollout
                        set mode = ?, lock_version = lock_version + 1,
                            updated_at = current_timestamp where workspace_id = ?
                        """, mode, workspaceId);
            }
        }
        return new RolloutState(mode, globalShadowEnabled && "SHADOW".equals(mode));
    }

    private String configuredMode(String workspaceId) {
        return jdbc.query("""
                select mode from context_v2_workspace_rollout where workspace_id = ?
                """, rs -> rs.next() ? rs.getString(1) : "OFF", workspaceId);
    }

    public record RolloutState(String mode, boolean shadowEffective) {}
}
