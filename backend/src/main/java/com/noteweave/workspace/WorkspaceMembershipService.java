package com.noteweave.workspace;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import com.noteweave.security.AuditActorProvider;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceMembershipService {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceAccessGuard accessGuard;
    private final AuditActorProvider auditActorProvider;

    public WorkspaceMembershipService(
            JdbcTemplate jdbcTemplate,
            WorkspaceAccessGuard accessGuard,
            AuditActorProvider auditActorProvider
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessGuard = accessGuard;
        this.auditActorProvider = auditActorProvider;
    }

    public List<WorkspaceMemberResponse> listMembers(String workspaceId) {
        accessGuard.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_ADMIN);
        return jdbcTemplate.query("""
                select wm.user_id, u.display_name, wm.role, wm.status, wm.updated_at
                from workspace_member wm
                join users u on u.id = wm.user_id
                where wm.workspace_id = ?
                order by case wm.role when 'OWNER' then 0 when 'EDITOR' then 1 else 2 end,
                         u.display_name, wm.user_id
                """, (rs, rowNum) -> new WorkspaceMemberResponse(
                rs.getString("user_id"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getTimestamp("updated_at").toInstant()
        ), workspaceId);
    }

    @Transactional
    public WorkspaceMemberResponse putMember(
            String workspaceId,
            String userId,
            UpdateWorkspaceMemberRequest request
    ) {
        accessGuard.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_ADMIN);
        String actor = auditActorProvider.currentOrSystem("WORKSPACE_MEMBERSHIP");
        requireNotOwner(workspaceId, userId);
        String displayName = jdbcTemplate.query("select display_name from users where id = ?",
                rs -> rs.next() ? rs.getString("display_name") : null, userId);
        if (displayName == null) {
            throw new BusinessException("USER_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND);
        }

        Integer existing = jdbcTemplate.queryForObject("""
                select count(*) from workspace_member where workspace_id = ? and user_id = ?
                """, Integer.class, workspaceId, userId);
        if (existing != null && existing > 0) {
            jdbcTemplate.update("""
                    update workspace_member
                    set role = ?, status = ?, updated_by = ?, updated_at = current_timestamp
                    where workspace_id = ? and user_id = ?
                    """, request.role(), request.status(), actor, workspaceId, userId);
        } else {
            jdbcTemplate.update("""
                    insert into workspace_member(id, workspace_id, user_id, role, status, created_by, updated_by)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), workspaceId, userId, request.role(), request.status(), actor, actor);
        }
        incrementAclVersion(workspaceId);
        return loadMember(workspaceId, userId, displayName);
    }

    @Transactional
    public void removeMember(String workspaceId, String userId) {
        accessGuard.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_ADMIN);
        String actor = auditActorProvider.currentOrSystem("WORKSPACE_MEMBERSHIP");
        requireNotOwner(workspaceId, userId);
        int updated = jdbcTemplate.update("""
                update workspace_member
                set status = 'REMOVED', updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and user_id = ? and status <> 'REMOVED'
                """, actor, workspaceId, userId);
        if (updated == 0) {
            throw new BusinessException("WORKSPACE_MEMBER_NOT_FOUND", "工作台成员不存在", HttpStatus.NOT_FOUND);
        }
        incrementAclVersion(workspaceId, actor);
    }

    private void requireNotOwner(String workspaceId, String userId) {
        String ownerId = jdbcTemplate.query("select owner_id from workspace where id = ?",
                rs -> rs.next() ? rs.getString("owner_id") : null, workspaceId);
        if (userId.equals(ownerId)) {
            throw new BusinessException("WORKSPACE_OWNER_IMMUTABLE", "不能通过成员接口修改工作台所有者",
                    HttpStatus.CONFLICT);
        }
    }

    private void incrementAclVersion(String workspaceId) {
        incrementAclVersion(workspaceId, auditActorProvider.currentOrSystem("WORKSPACE_MEMBERSHIP"));
    }

    private void incrementAclVersion(String workspaceId, String actor) {
        jdbcTemplate.update("""
                update workspace
                set acl_version = acl_version + 1, updated_by = ?, updated_at = current_timestamp
                where id = ?
                """, actor, workspaceId);
    }

    private WorkspaceMemberResponse loadMember(String workspaceId, String userId, String displayName) {
        return jdbcTemplate.query("""
                select role, status, updated_at from workspace_member where workspace_id = ? and user_id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("WORKSPACE_MEMBER_NOT_FOUND", "工作台成员不存在", HttpStatus.NOT_FOUND);
            }
            Timestamp updatedAt = rs.getTimestamp("updated_at");
            return new WorkspaceMemberResponse(userId, displayName, rs.getString("role"),
                    rs.getString("status"), updatedAt.toInstant());
        }, workspaceId, userId);
    }
}
