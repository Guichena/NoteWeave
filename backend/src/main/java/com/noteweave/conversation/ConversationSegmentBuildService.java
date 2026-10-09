package com.noteweave.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.task.TaskService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ConversationSegmentBuildService {

    static final int RAW_TAIL_MESSAGE_LIMIT = 8;
    static final int MINIMUM_SUMMARY_SOURCE_MESSAGES = 3;
    static final String SUMMARY_TOPIC = "noteweave.conversation.summary";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TaskService taskService;

    public ConversationSegmentBuildService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            TaskService taskService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.taskService = taskService;
    }

    /**
     * Queues a build only for the active prefix that has just fallen outside the raw tail.
     * The outbox payload is the immutable worker input; the worker cannot reread a newer path.
     */
    public void queueBuildForActivePrefix(String workspaceId, String conversationId) {
        Integer maximumSequence = jdbcTemplate.queryForObject("""
                select max(message_seq)
                from conversation_message
                where conversation_id = ? and context_status = 'CURRENT'
                """, Integer.class, conversationId);
        if (maximumSequence == null) {
            return;
        }
        // The next submission contributes one CURRENT user message before its snapshot is compiled.
        // Leave exactly RAW_TAIL_MESSAGE_LIMIT messages after this prefix at that boundary.
        int coveredEnd = maximumSequence - RAW_TAIL_MESSAGE_LIMIT + 1;
        if (coveredEnd < MINIMUM_SUMMARY_SOURCE_MESSAGES) {
            return;
        }

        Integer existing = jdbcTemplate.queryForObject("""
                select count(*)
                from conversation_segment
                where conversation_id = ? and covered_start_seq = 1 and covered_end_seq = ?
                """, Integer.class, conversationId, coveredEnd);
        if (existing != null && existing > 0) {
            return;
        }

        // 增量摘要：以覆盖范围更短的最新 READY 前缀摘要为起点，只把之后新增的消息交给摘要任务。
        // 前缀摘要都从第 1 条消息开始，旧摘要覆盖的消息被删除时，新摘要所在的前缀也会一起失效。
        BaseSummary base = latestReadyPrefixBefore(conversationId, coveredEnd);
        int deltaStart = base == null ? 1 : base.coveredEndSeq() + 1;
        List<FrozenMessage> messages = jdbcTemplate.query("""
                select id, message_seq, role, content, content_hash
                from conversation_message
                where conversation_id = ? and context_status = 'CURRENT'
                  and message_seq between ? and ?
                order by message_seq
                """, (rs, rowNum) -> new FrozenMessage(
                rs.getString("id"),
                rs.getInt("message_seq"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getString("content_hash")
        ), conversationId, deltaStart, coveredEnd);
        if (messages.size() != coveredEnd - deltaStart + 1) {
            return;
        }

        String segmentId = Ids.newId();
        String revisionId = Ids.newId();
        jdbcTemplate.update("""
                insert into conversation_segment(
                    id, workspace_id, conversation_id, covered_start_seq, covered_end_seq, lock_version
                ) values (?, ?, ?, 1, ?, 0)
                """, segmentId, workspaceId, conversationId, coveredEnd);
        jdbcTemplate.update("""
                insert into segment_summary_revision(
                    id, segment_id, revision_no, status, source_segment_version, base_revision_id
                ) values (?, ?, 1, 'BUILDING', 0, ?)
                """, revisionId, segmentId, base == null ? null : base.revisionId());

        String taskId = taskService.createTask(
                workspaceId,
                "CONVERSATION_SUMMARY",
                "SEGMENT_SUMMARY_REVISION",
                revisionId,
                "QUEUED",
                "Conversation segment summary queued"
        );
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", taskId);
        payload.put("workspace_id", workspaceId);
        payload.put("conversation_id", conversationId);
        payload.put("segment_id", segmentId);
        payload.put("summary_revision_id", revisionId);
        payload.put("source_segment_version", 0);
        payload.put("covered_start_seq", 1);
        payload.put("covered_end_seq", coveredEnd);
        if (base != null) {
            payload.put("base_summary_revision_id", base.revisionId());
            payload.put("base_covered_end_seq", base.coveredEndSeq());
            payload.put("base_summary_text", base.summaryText());
        }
        payload.put("source_messages", messages.stream().map(message -> Map.of(
                "message_id", message.messageId(),
                "message_seq", message.messageSequence(),
                "role", message.role(),
                "content", message.content(),
                "content_hash", message.contentHash() == null || message.contentHash().isBlank()
                        ? sha256(message.content()) : message.contentHash()
        )).toList());
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, ?, ?, ?, 'READY')
                """, Ids.newId(), taskId, SUMMARY_TOPIC, revisionId, Json.write(objectMapper, payload));
    }

    public List<ConversationSegmentSummaryBuildResponse> listBuilds(String workspaceId, String conversationId) {
        Integer conversationCount = jdbcTemplate.queryForObject("""
                select count(*) from conversation where id = ? and workspace_id = ?
                """, Integer.class, conversationId, workspaceId);
        if (conversationCount == null || conversationCount == 0) {
            throw new com.noteweave.common.BusinessException("CONVERSATION_NOT_FOUND", "Conversation not found");
        }
        return jdbcTemplate.query("""
                select s.id segment_id, r.id revision_id, s.covered_start_seq, s.covered_end_seq,
                       r.status revision_status, t.id task_id, t.task_type, t.task_status
                from conversation_segment s
                join segment_summary_revision r on r.segment_id = s.id
                left join task t on t.target_type = 'SEGMENT_SUMMARY_REVISION' and t.target_id = r.id
                where s.workspace_id = ? and s.conversation_id = ?
                order by s.covered_end_seq asc, r.revision_no asc
                """, (rs, rowNum) -> new ConversationSegmentSummaryBuildResponse(
                rs.getString("segment_id"), rs.getString("revision_id"),
                rs.getInt("covered_start_seq"), rs.getInt("covered_end_seq"),
                rs.getString("revision_status"), rs.getString("task_id"),
                rs.getString("task_type"), rs.getString("task_status")
        ), workspaceId, conversationId);
    }

    private BaseSummary latestReadyPrefixBefore(String conversationId, int coveredEnd) {
        List<BaseSummary> rows = jdbcTemplate.query("""
                select r.id, s.covered_end_seq, r.summary_text
                from conversation_segment s
                join segment_summary_revision r on r.segment_id = s.id
                where s.conversation_id = ? and s.covered_start_seq = 1 and s.covered_end_seq < ?
                  and r.status = 'READY'
                order by s.covered_end_seq desc, r.revision_no desc
                """, (rs, rowNum) -> new BaseSummary(rs.getString(1), rs.getInt(2), rs.getString(3)),
                conversationId, coveredEnd);
        return rows.isEmpty() || rows.get(0).summaryText() == null || rows.get(0).summaryText().isBlank()
                ? null : rows.get(0);
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record BaseSummary(String revisionId, int coveredEndSeq, String summaryText) {
    }

    private record FrozenMessage(
            String messageId,
            int messageSequence,
            String role,
            String content,
            String contentHash
    ) {
    }
}
