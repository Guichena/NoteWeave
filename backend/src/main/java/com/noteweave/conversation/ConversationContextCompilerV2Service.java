package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import com.noteweave.memory.MemoryReferenceResponse;
import com.noteweave.memory.MemoryRuntime;
import com.noteweave.memory.MemoryRuntimeQuery;
import com.noteweave.security.CurrentUserProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-backed shadow compiler. Callers must persist its result before a task consumes it. */
@Service
public class ConversationContextCompilerV2Service {
    private final JdbcTemplate jdbc;
    private final WorkspaceAccessGuard access;
    private final CurrentUserProvider users;
    private final MemoryRuntime memory;
    private final ConversationTopicSummaryV2Service summaries;
    private final ContextWindowPlannerV2 planner = new ContextWindowPlannerV2();

    public ConversationContextCompilerV2Service(JdbcTemplate jdbc, WorkspaceAccessGuard access,
                                                CurrentUserProvider users, MemoryRuntime memory,
                                                ConversationTopicSummaryV2Service summaries) {
        this.jdbc = jdbc;
        this.access = access;
        this.users = users;
        this.memory = memory;
        this.summaries = summaries;
    }

    @Transactional(readOnly = true)
    public ContextProjectionV2 compile(String workspaceId, String actorId, String conversationId,
                                       int cutoffSeq, String currentInput, String taskPurpose,
                                       int tokenBudget) {
        access.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_READ);
        if (!users.requireUserId().equals(actorId)) {
            throw new BusinessException("CONTEXT_ACTOR_MISMATCH",
                    "Context actor does not match the authenticated user", HttpStatus.FORBIDDEN);
        }
        if (conversationId == null || conversationId.isBlank()) {
            if (cutoffSeq != 0) throw invalid("independent context cannot have a conversation cutoff");
            return planner.compile(new ContextWindowPlannerV2.Input(workspaceId, actorId, "", 0,
                    currentInput, taskPurpose, tokenBudget, List.of(), List.of(), List.of(), List.of(),
                    memoryReferences(workspaceId)));
        }
        Integer exists = jdbc.queryForObject("""
                select count(*) from conversation where id = ? and workspace_id = ?
                """, Integer.class, conversationId, workspaceId);
        if (exists == null || exists != 1) throw invalid("conversation does not belong to Workspace");
        if (cutoffSeq < 0) throw invalid("conversation cutoff is negative");
        Integer ledgerHead = jdbc.queryForObject("""
                select max(message_seq) from conversation_message
                where workspace_id = ? and conversation_id = ?
                """, Integer.class, workspaceId, conversationId);
        if ((ledgerHead == null ? 0 : ledgerHead) != cutoffSeq)
            throw invalid("shadow compilation requires the current ledger head");
        List<MessageRow> rows = jdbc.query("""
                select id, message_seq, role, content, context_status
                from conversation_message where workspace_id = ? and conversation_id = ?
                  and message_seq <= ? order by message_seq
                """, (rs, index) -> new MessageRow(rs.getString(1), rs.getInt(2),
                rs.getString(3), rs.getString(4), rs.getString(5)),
                workspaceId, conversationId, cutoffSeq);
        if (rows.size() != cutoffSeq) throw invalid("conversation cutoff is not a complete ledger prefix");
        List<ContextProjectionV2.RawMessage> messages = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            MessageRow row = rows.get(index);
            if (row.seq() != index + 1 || !"CURRENT".equals(row.status()))
                throw invalid("conversation prefix contains pending or deleted messages");
            messages.add(new ContextProjectionV2.RawMessage(row.id(), row.seq(), row.role(),
                    row.text(), sha256(row.text())));
        }
        List<TopicSegmenterV2.Segment> segments = jdbc.query("""
                select id, topic_id, start_seq, end_seq, decision_status, decision_reason, rule_version
                from conversation_topic_segment_v2
                where workspace_id = ? and conversation_id = ? and start_seq <= ?
                order by start_seq
                """, (rs, index) -> new TopicSegmenterV2.Segment(rs.getString(1), rs.getString(2),
                rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7)),
                workspaceId, conversationId, cutoffSeq);
        int nextSeq = 1;
        for (TopicSegmenterV2.Segment segment : segments) {
            if (segment.startSeq() != nextSeq || segment.endSeq() > cutoffSeq
                    || "STALE".equals(segment.status()))
                throw invalid("topic projection is missing, stale or newer than cutoff");
            nextSeq = segment.endSeq() + 1;
        }
        if (nextSeq != cutoffSeq + 1) throw invalid("topic projection does not cover cutoff");
        List<ContextProjectionV2.UserConstraint> constraints = jdbc.query("""
                select c.id, c.source_message_id, m.role, c.kind, c.scope, c.constraint_text,
                       c.valid_from_seq, c.invalid_after_seq, c.status
                from conversation_constraint_v2 c
                join conversation_message m on m.id = c.source_message_id
                where c.workspace_id = ? and c.conversation_id = ? and c.valid_from_seq <= ?
                order by c.valid_from_seq, c.id
                """, (rs, index) -> new ContextProjectionV2.UserConstraint(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7),
                rs.getObject(8) == null ? null : rs.getInt(8), rs.getString(9)),
                workspaceId, conversationId, cutoffSeq);
        return planner.compile(new ContextWindowPlannerV2.Input(workspaceId, actorId, conversationId,
                cutoffSeq, currentInput, taskPurpose, tokenBudget, messages, segments,
                summaries.ready(workspaceId, conversationId, cutoffSeq), constraints,
                memoryReferences(workspaceId)));
    }

    private List<ContextProjectionV2.MemoryRevision> memoryReferences(String workspaceId) {
        List<ContextProjectionV2.MemoryRevision> result = new ArrayList<>();
        for (MemoryReferenceResponse ref : memory.recall(new MemoryRuntimeQuery(workspaceId)).memoryReferences()) {
            List<ContextProjectionV2.MemoryRevision> selected = jdbc.query("""
                    select i.id, r.id, r.display_text from memory_item i
                    join memory_runtime_revision r on r.id = i.current_revision_id
                    where i.id = ? and r.id = ? and i.workspace_id = ?
                      and i.status = 'ACTIVE' and r.status = 'ACTIVE'
                      and r.valid_from <= current_timestamp
                      and (r.valid_until is null or r.valid_until > current_timestamp)
                    """, (rs, index) -> new ContextProjectionV2.MemoryRevision(
                    rs.getString(1), rs.getString(2), rs.getString(3), sha256(rs.getString(3))),
                    ref.memoryObjectId(), ref.memoryVersionId(), workspaceId);
            result.addAll(selected);
        }
        return List.copyOf(result);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("CONTEXT_V2_SOURCE_INVALID", message, HttpStatus.CONFLICT);
    }

    private record MessageRow(String id, int seq, String role, String text, String status) {}
}
