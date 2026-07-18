package com.noteweave.security;

import com.noteweave.common.BusinessException;
import com.noteweave.common.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.Locale;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceAccessGuard {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUserProvider;
    private final WorkspaceAclCache aclCache;
    private final MeterRegistry meterRegistry;

    public WorkspaceAccessGuard(
            JdbcTemplate jdbcTemplate,
            CurrentUserProvider currentUserProvider,
            WorkspaceAclCache aclCache,
            MeterRegistry meterRegistry
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserProvider = currentUserProvider;
        this.aclCache = aclCache;
        this.meterRegistry = meterRegistry;
    }

    public void requireMember(String workspaceId) {
        requirePermission(workspaceId, WorkspacePermission.WORKSPACE_READ);
    }

    public void requireOwner(String workspaceId) {
        requirePermission(workspaceId, WorkspacePermission.WORKSPACE_ADMIN);
    }

    public void requirePermission(String workspaceId, WorkspacePermission permission) {
        MDC.put(RequestContext.WORKSPACE_ID, workspaceId);
        String userId = currentUserProvider.requireUserId();
        WorkspaceState workspace = jdbcTemplate.query("""
                select status, acl_version from workspace where id = ?
                """, rs -> rs.next() ? new WorkspaceState(rs.getString("status"), rs.getLong("acl_version")) : null,
                workspaceId);
        if (workspace == null) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在", HttpStatus.NOT_FOUND);
        }
        if (!"ACTIVE".equals(workspace.status())) {
            deny(permission);
        }

        WorkspaceAclCache.Lookup cached = aclCache.get(
                workspaceId, userId, workspace.aclVersion());
        if (cached.denied()) {
            deny(permission);
        }
        WorkspaceRole role = cached.role()
                .orElseGet(() -> loadRole(workspaceId, userId, workspace.aclVersion()));
        if (!role.grants(permission)) {
            deny(permission);
        }
    }

    public void requireKnowledgeItemPermission(String itemId, WorkspacePermission permission) {
        requireResourcePermission("knowledge_item", itemId, "KNOWLEDGE_ITEM_NOT_FOUND", permission);
    }

    public void requireMessagePermission(String messageId, WorkspacePermission permission) {
        requireResourcePermission("conversation_message", messageId, "MESSAGE_NOT_FOUND", permission);
    }

    public void requireTaskPermission(String taskId, WorkspacePermission permission) {
        requireResourcePermission("task", taskId, "TASK_NOT_FOUND", permission);
    }

    public void requireConversationPermission(String conversationId, WorkspacePermission permission) {
        requireResourcePermission("conversation", conversationId, "CONVERSATION_NOT_FOUND", permission);
    }

    public void requireUploadPermission(String uploadId, WorkspacePermission permission) {
        requireResourcePermission("document_upload", uploadId, "UPLOAD_NOT_FOUND", permission);
    }

    public void requireChatRequestPermission(String assistantRequestId, WorkspacePermission permission) {
        String workspaceId = jdbcTemplate.query("""
                select workspace_id from conversation_message where assistant_request_id = ?
                """, rs -> rs.next() ? rs.getString("workspace_id") : null, assistantRequestId);
        if (workspaceId == null) {
            throw new BusinessException("CHAT_REQUEST_NOT_FOUND", "聊天请求不存在", HttpStatus.NOT_FOUND);
        }
        requirePermission(workspaceId, permission);
    }

    private WorkspaceRole loadRole(String workspaceId, String userId, long aclVersion) {
        long startedAt = System.nanoTime();
        String roleValue;
        try {
            roleValue = jdbcTemplate.query("""
                    select wm.role
                    from workspace_member wm
                    join users u on u.id = wm.user_id
                    where wm.workspace_id = ? and wm.user_id = ?
                      and wm.status = 'ACTIVE' and u.status = 'ACTIVE'
                    """, rs -> rs.next() ? rs.getString("role") : null, workspaceId, userId);
        } catch (RuntimeException exception) {
            recordAclLoad("error", startedAt);
            throw exception;
        }
        if (roleValue == null) {
            recordAclLoad("denied", startedAt);
            aclCache.putDenied(workspaceId, userId, aclVersion);
            deny(WorkspacePermission.WORKSPACE_READ);
        }
        try {
            WorkspaceRole role = WorkspaceRole.valueOf(roleValue.toUpperCase(Locale.ROOT));
            recordAclLoad("granted", startedAt);
            aclCache.put(workspaceId, userId, aclVersion, role);
            return role;
        } catch (IllegalArgumentException exception) {
            recordAclLoad("invalid_role", startedAt);
            aclCache.putDenied(workspaceId, userId, aclVersion);
            deny(WorkspacePermission.WORKSPACE_READ);
            throw new IllegalStateException("unreachable");
        }
    }

    private void recordAclLoad(String result, long startedAt) {
        meterRegistry.timer("noteweave.security.acl.db.load", "result", result)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }

    private void requireResourcePermission(
            String table,
            String resourceId,
            String notFoundCode,
            WorkspacePermission permission
    ) {
        String workspaceId = jdbcTemplate.query("select workspace_id from " + table + " where id = ?",
                rs -> rs.next() ? rs.getString("workspace_id") : null, resourceId);
        if (workspaceId == null) {
            throw new BusinessException(notFoundCode, "资源不存在", HttpStatus.NOT_FOUND);
        }
        requirePermission(workspaceId, permission);
    }

    private void deny(WorkspacePermission permission) {
        meterRegistry.counter("noteweave.security.access.denied",
                "resource", "workspace", "permission", permission.name()).increment();
        throw new BusinessException("WORKSPACE_ACCESS_DENIED", "无权执行该工作台操作", HttpStatus.FORBIDDEN);
    }

    private record WorkspaceState(String status, long aclVersion) {
    }
}
