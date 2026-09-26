package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.task.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable, segment-scoped v2 summary revisions built from frozen ledger rows. */
@Service
public class ConversationTopicSummaryV2Service {
    static final String COMPILER_VERSION = "topic-summary-v2-extractive-a1";
    private static final String SUMMARY_TOPIC = "noteweave.conversation.summary";
    private static final int RAW_TAIL = 8;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TaskService tasks;

    public ConversationTopicSummaryV2Service(JdbcTemplate jdbc, ObjectMapper mapper, TaskService tasks) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tasks = tasks;
    }

    @Transactional
    public int queueBuilds(String workspaceId, String conversationId) {
        List<String> locked = jdbc.query("""
                select id from conversation where id = ? and workspace_id = ? for update
                """, (rs, index) -> rs.getString(1), conversationId, workspaceId);
        if (locked.size() != 1) throw invalid("conversation does not belong to Workspace");
        Integer lastSeq = jdbc.queryForObject("""
                select max(message_seq) from conversation_message
                where workspace_id = ? and conversation_id = ? and context_status = 'CURRENT'
                """, Integer.class, workspaceId, conversationId);
        if (lastSeq == null || lastSeq <= RAW_TAIL) return 0;
        int coveredEnd = lastSeq - RAW_TAIL;
        List<Segment> segments = jdbc.query("""
                select id, topic_id, start_seq, end_seq from conversation_topic_segment_v2
                where workspace_id = ? and conversation_id = ? and decision_status = 'CONFIDENT'
                  and start_seq <= ? order by start_seq
                """, (rs, index) -> new Segment(rs.getString(1), rs.getString(2),
                rs.getInt(3), rs.getInt(4)), workspaceId, conversationId, coveredEnd);
        int queued = 0;
        for (Segment segment : segments) {
            int end = Math.min(segment.endSeq(), coveredEnd);
            Integer existing = jdbc.queryForObject("""
                    select count(*) from conversation_topic_summary_revision_v2
                    where segment_id = ? and end_seq = ?
                    """, Integer.class, segment.id(), end);
            if (existing != null && existing > 0) continue;
            List<SourceMessage> messages = loadMessages(workspaceId, conversationId,
                    segment.startSeq(), end);
            if (messages.size() != end - segment.startSeq() + 1) continue;
            String revisionId = Ids.newId();
            Integer maximum = jdbc.queryForObject("""
                    select max(revision_no) from conversation_topic_summary_revision_v2
                    where segment_id = ?
                    """, Integer.class, segment.id());
            int revisionNo = maximum == null ? 1 : maximum + 1;
            String digest = sourceDigest(messages);
            jdbc.update("""
                    insert into conversation_topic_summary_revision_v2(
                        id, workspace_id, conversation_id, topic_id, segment_id, revision_no,
                        start_seq, end_seq, source_digest, status, summary_text, compiler_version)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'BUILDING', '', ?)
                    """, revisionId, workspaceId, conversationId, segment.topicId(), segment.id(),
                    revisionNo, segment.startSeq(), end, digest, COMPILER_VERSION);
            String taskId = tasks.createTask(workspaceId, "CONVERSATION_SUMMARY",
                    "TOPIC_SUMMARY_REVISION_V2", revisionId, "QUEUED",
                    "Conversation topic summary queued");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("summary_projection_version", 2);
            payload.put("task_id", taskId);
            payload.put("workspace_id", workspaceId);
            payload.put("conversation_id", conversationId);
            payload.put("segment_id", segment.id());
            payload.put("summary_revision_id", revisionId);
            payload.put("covered_start_seq", segment.startSeq());
            payload.put("covered_end_seq", end);
            payload.put("source_digest", digest);
            payload.put("source_messages", messages.stream().map(message -> Map.of(
                    "message_id", message.id(), "message_seq", message.seq(),
                    "role", message.role(), "content", message.text(),
                    "content_hash", sha256(message.text()))).toList());
            jdbc.update("""
                    insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                    values (?, ?, ?, ?, ?, 'READY')
                    """, Ids.newId(), taskId, SUMMARY_TOPIC, revisionId, Json.write(mapper, payload));
            queued++;
        }
        return queued;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public void promote(String segmentId, String revisionId, PromoteSegmentSummaryRequest request) {
        Revision revision = jdbc.query("""
                select r.workspace_id, r.conversation_id, r.start_seq, r.end_seq,
                       r.source_digest, r.status, s.start_seq, s.end_seq,
                       s.decision_status, t.status
                from conversation_topic_summary_revision_v2 r
                join conversation_topic_segment_v2 s on s.id = r.segment_id
                join conversation_topic_v2 t on t.id = r.topic_id
                where r.id = ? and r.segment_id = ? for update
                """, rs -> rs.next() ? new Revision(rs.getString(1), rs.getString(2),
                rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getString(6),
                rs.getInt(7), rs.getInt(8), rs.getString(9), rs.getString(10)) : null,
                revisionId, segmentId);
        if (revision == null || !"BUILDING".equals(revision.status()))
            throw invalid("topic summary revision is not building");
        List<SourceMessage> messages = loadMessages(revision.workspaceId(), revision.conversationId(),
                revision.startSeq(), revision.endSeq());
        if (revision.startSeq() != revision.segmentStart()
                || revision.endSeq() > revision.segmentEnd()
                || !"CONFIDENT".equals(revision.segmentStatus())
                || !"ACTIVE".equals(revision.topicStatus())
                || messages.size() != revision.endSeq() - revision.startSeq() + 1
                || !revision.sourceDigest().equals(sourceDigest(messages))) {
            jdbc.update("""
                    update conversation_topic_summary_revision_v2
                    set status = 'STALE', summary_text = '', content_hash = null
                    where id = ? and status = 'BUILDING'
                    """, revisionId);
            throw new BusinessException("CONTEXT_TOPIC_SUMMARY_STALE",
                    "Topic source changed before summary promotion", HttpStatus.CONFLICT);
        }
        String expectedSummary = messages.stream()
                .map(message -> summarizeMessage(message.role(), message.text()))
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (expectedSummary.length() > 16_000) expectedSummary = expectedSummary.substring(0, 16_000);
        if (request == null || request.summaryText() == null || request.summaryText().isBlank()
                || !expectedSummary.equals(request.summaryText())
                || !sha256(request.summaryText()).equals(request.contentHash()))
            throw invalid("topic summary text or digest is invalid");
        int promoted = jdbc.update("""
                update conversation_topic_summary_revision_v2
                set status = 'READY', summary_text = ?, content_hash = ?, ready_at = current_timestamp
                where id = ? and status = 'BUILDING'
                """, request.summaryText(), request.contentHash(), revisionId);
        if (promoted != 1) throw invalid("topic summary promotion conflicted");
        for (String taskId : jdbc.query("""
                select id from task where target_type = 'TOPIC_SUMMARY_REVISION_V2'
                  and target_id = ? and task_status in ('PENDING', 'RUNNING', 'WAITING')
                """, (rs, index) -> rs.getString(1), revisionId)) {
            String status = jdbc.queryForObject("select task_status from task where id = ?",
                    String.class, taskId);
            if ("PENDING".equals(status)) tasks.startTask(taskId);
            tasks.completeTask(taskId, "COMPLETED", "Conversation topic summary is ready", segmentId);
        }
    }

    public List<ContextProjectionV2.TopicSummary> ready(String workspaceId, String conversationId, int cutoffSeq) {
        return jdbc.query("""
                select r.topic_id, r.segment_id, r.id, r.start_seq, r.end_seq,
                       r.summary_text, r.content_hash
                from conversation_topic_summary_revision_v2 r
                join conversation_topic_segment_v2 s on s.id = r.segment_id
                join conversation_topic_v2 t on t.id = r.topic_id
                where r.workspace_id = ? and r.conversation_id = ? and r.end_seq <= ?
                  and r.status = 'READY' and s.decision_status = 'CONFIDENT'
                  and t.status = 'ACTIVE'
                order by r.end_seq, r.revision_no
                """, (rs, index) -> new ContextProjectionV2.TopicSummary(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getString(7)),
                workspaceId, conversationId, cutoffSeq);
    }

    private List<SourceMessage> loadMessages(String workspaceId, String conversationId, int start, int end) {
        return jdbc.query("""
                select id, message_seq, role, content from conversation_message
                where workspace_id = ? and conversation_id = ? and context_status = 'CURRENT'
                  and message_seq between ? and ? order by message_seq
                """, (rs, index) -> new SourceMessage(rs.getString(1), rs.getInt(2),
                rs.getString(3), rs.getString(4)),
                workspaceId, conversationId, start, end);
    }

    private static String sourceDigest(List<SourceMessage> messages) {
        List<String> refs = new ArrayList<>();
        for (SourceMessage message : messages) {
            String contentHash = sha256(message.text());
            refs.add(message.id() + ":" + message.seq() + ":" + contentHash);
        }
        return sha256(String.join("\n", refs));
    }

    public static String summarizeMessage(String role, String content) {
        String normalized = content == null ? "" : content.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.isBlank()) return "";
        String bounded = normalized.substring(0, Math.min(600, normalized.length()));
        return (role == null || role.isBlank() ? "message" : role.toLowerCase(Locale.ROOT))
                + ": " + bounded;
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
        return new BusinessException("CONTEXT_TOPIC_SUMMARY_INVALID", message, HttpStatus.CONFLICT);
    }

    private record Segment(String id, String topicId, int startSeq, int endSeq) {}
    private record SourceMessage(String id, int seq, String role, String text) {}
    private record Revision(String workspaceId, String conversationId, int startSeq, int endSeq,
                            String sourceDigest, String status, int segmentStart, int segmentEnd,
                            String segmentStatus, String topicStatus) {}
}
