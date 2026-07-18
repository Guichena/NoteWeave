package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import com.noteweave.security.AuditActorProvider;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final AuditActorProvider auditActorProvider;

    public ConversationService(
            JdbcTemplate jdbcTemplate,
            WorkspaceAccessGuard workspaceAccessGuard,
            AuditActorProvider auditActorProvider
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.auditActorProvider = auditActorProvider;
    }

    @Transactional
    public ConversationResponse createConversation(String workspaceId, CreateConversationRequest request) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.ANSWER_RUN);
        String actor = auditActorProvider.currentOrSystem("CONVERSATION");
        String conversationId = Ids.newId();
        jdbcTemplate.update("""
                insert into conversation(id, workspace_id, title, conversation_type, status, created_by, updated_by)
                values (?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, conversationId, workspaceId, request.title(), request.conversationType(), actor, actor);
        return new ConversationResponse(conversationId, request.title(), request.conversationType(), Instant.now());
    }

    public void requireConversation(String workspaceId, String conversationId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from conversation where workspace_id = ? and id = ?
                """, Integer.class, workspaceId, conversationId);
        if (count == null || count == 0) {
            throw new BusinessException(
                    "CONVERSATION_NOT_FOUND",
                    "Conversation does not exist in this workspace",
                    org.springframework.http.HttpStatus.NOT_FOUND
            );
        }
    }

    public List<ConversationSummaryResponse> listConversations(String workspaceId) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_READ);
        return jdbcTemplate.query("""
                select id, title, conversation_type, status, active_head_message_id,
                       created_at, last_active_at
                from conversation
                where workspace_id = ?
                order by last_active_at desc, created_at desc
                """, (rs, rowNum) -> new ConversationSummaryResponse(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("conversation_type"),
                rs.getString("status"),
                rs.getString("active_head_message_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_active_at").toInstant()
        ), workspaceId);
    }

    public List<ConversationMessageResponse> listMessages(
            String workspaceId,
            String conversationId,
            int afterSequence,
            int limit
    ) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_READ);
        requireConversation(workspaceId, conversationId);
        int safeAfter = Math.max(0, afterSequence);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.query("""
                select id, message_seq, role, answer_mode, content, reply_to_message_id,
                       context_status, content_hash, created_at
                from conversation_message
                where workspace_id = ? and conversation_id = ? and message_seq > ?
                order by message_seq asc
                limit ?
                """, (rs, rowNum) -> new ConversationMessageResponse(
                rs.getString("id"),
                rs.getInt("message_seq"),
                rs.getString("role"),
                rs.getString("answer_mode"),
                rs.getString("content"),
                rs.getString("reply_to_message_id"),
                rs.getString("context_status"),
                rs.getString("content_hash"),
                rs.getTimestamp("created_at").toInstant()
        ), workspaceId, conversationId, safeAfter, safeLimit);
    }
}
