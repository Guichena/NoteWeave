package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final AuditActorProvider auditActorProvider;
    private final ResearchGeneratedSourceReadGate generatedSourceGate;

    public ConversationService(
            JdbcTemplate jdbcTemplate,
            WorkspaceAccessGuard workspaceAccessGuard,
            AuditActorProvider auditActorProvider,
            ResearchGeneratedSourceReadGate generatedSourceGate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.auditActorProvider = auditActorProvider;
        this.generatedSourceGate = generatedSourceGate;
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
        List<ConversationMessageResponse> messages = jdbcTemplate.query("""
                select m.id, m.message_seq, m.role, m.answer_mode, m.content, m.reply_to_message_id,
                       m.context_status, m.content_hash, r.status as answer_status,
                       r.error_message as answer_error, m.created_at
                from conversation_message m
                left join answer_run r on r.assistant_request_id = m.assistant_request_id
                where m.workspace_id = ? and m.conversation_id = ? and m.message_seq > ?
                order by m.message_seq asc
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
                rs.getString("answer_status"),
                rs.getString("answer_error"),
                rs.getTimestamp("created_at").toInstant()
        ), workspaceId, conversationId, safeAfter, safeLimit);
        if (messages.isEmpty()) return messages;
        List<String> ids = messages.stream().map(ConversationMessageResponse::messageId).toList();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] parameters = new Object[ids.size() + 1];
        parameters[0] = workspaceId;
        for (int i = 0; i < ids.size(); i++) parameters[i + 1] = ids.get(i);
        Set<String> redacted = new HashSet<>(jdbcTemplate.queryForList("""
                select m.id from conversation_message m
                join answer_run r on r.assistant_request_id = m.assistant_request_id
                join run_input_snapshot s on s.answer_run_id = r.id
                where m.workspace_id = ? and m.id in (%s)
                  and s.replay_availability = 'METADATA_ONLY'
                """.formatted(placeholders), String.class, parameters));
        Map<String, List<String>> sourcesByMessage = new HashMap<>();
        jdbcTemplate.query("""
                select mc.message_id, c.source_id
                from conversation_message m
                join message_citation mc on mc.message_id = m.id
                join citation c on c.id = mc.citation_id
                where m.workspace_id = ? and m.id in (%s)
                """.formatted(placeholders), rs -> {
            sourcesByMessage.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>())
                    .add(rs.getString(2));
        }, parameters);
        List<String> citedSources = sourcesByMessage.values().stream().flatMap(List::stream)
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        Set<String> readableSources = generatedSourceGate.readableSourceIds(workspaceId, citedSources);
        sourcesByMessage.forEach((messageId, sourceIds) -> {
            if (!readableSources.containsAll(sourceIds)) redacted.add(messageId);
        });
        return messages.stream().map(message -> redacted.contains(message.messageId())
                && "ASSISTANT".equals(message.role())
                ? new ConversationMessageResponse(message.messageId(), message.messageSeq(),
                    message.role(), message.requestedTurnMode(),
                    "此回答引用的资料已撤销，内容不可查看。", message.replyToMessageId(),
                    "REDACTED", null, message.answerStatus(), null, message.createdAt())
                : message).toList();
    }
}
