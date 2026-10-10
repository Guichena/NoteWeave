package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shadow C1 projection. Published v1 context selection remains unchanged. */
@Service
public class ConversationTopicProjectionV2Service {
    private final JdbcTemplate jdbc;
    private final ConversationTopicSummaryV2Service summaries;
    private final TopicSegmenterV2 segmenter = new TopicSegmenterV2();
    private final ConversationConstraintProjectorV2 constraintProjector = new ConversationConstraintProjectorV2();

    public ConversationTopicProjectionV2Service(JdbcTemplate jdbc,
                                                 ConversationTopicSummaryV2Service summaries) {
        this.jdbc = jdbc;
        this.summaries = summaries;
    }

    @Transactional
    public TopicSegmenterV2.Projection refresh(String workspaceId, String conversationId) {
        return refreshAtCutoff(workspaceId, conversationId, null);
    }

    /** Freeze the query prefix while the immediately following assistant placeholder is pending. */
    @Transactional
    public TopicSegmenterV2.Projection refreshForInputCutoff(String workspaceId,
                                                               String conversationId, int cutoffSeq) {
        return refreshAtCutoff(workspaceId, conversationId, cutoffSeq);
    }

    private TopicSegmenterV2.Projection refreshAtCutoff(String workspaceId,
                                                        String conversationId, Integer cutoffSeq) {
        List<String> locked = jdbc.query("""
                select id from conversation where id = ? and workspace_id = ? for update
                """, (rs, index) -> rs.getString(1), conversationId, workspaceId);
        if (locked.isEmpty()) {
            throw new BusinessException("CONVERSATION_NOT_FOUND", "Conversation does not belong to Workspace",
                    HttpStatus.NOT_FOUND);
        }
        if (cutoffSeq != null) {
            ConversationInputCutoff.requirePendingAssistant(jdbc, workspaceId, conversationId, cutoffSeq);
        }
        List<MessageRow> rows = jdbc.query("""
                select id, message_seq, role, content, content_hash, context_status
                from conversation_message where conversation_id = ? and workspace_id = ?
                  and (? is null or message_seq <= ?)
                order by message_seq
                """, (rs, index) -> new MessageRow(rs.getString("id"), rs.getInt("message_seq"),
                rs.getString("role"), rs.getString("content"), rs.getString("content_hash"),
                rs.getString("context_status")), conversationId, workspaceId, cutoffSeq, cutoffSeq);
        if (rows.stream().anyMatch(row -> !"CURRENT".equals(row.contextStatus()))) {
            throw new BusinessException("CONTEXT_TOPIC_REDACTED",
                    "Deleted messages require topic redaction before shadow projection refresh",
                    HttpStatus.CONFLICT);
        }
        // 已经落库的话题划分不回溯修改：作为固定前缀交给切分器，只对之后的消息做新的判断
        List<TopicSegmenterV2.Segment> persisted = jdbc.query("""
                select id, topic_id, start_seq, end_seq, decision_status, decision_reason, rule_version
                from conversation_topic_segment_v2
                where conversation_id = ? and decision_status <> 'STALE' order by start_seq
                """, (rs, index) -> new TopicSegmenterV2.Segment(rs.getString("id"), rs.getString("topic_id"),
                rs.getInt("start_seq"), rs.getInt("end_seq"), rs.getString("decision_status"),
                rs.getString("decision_reason"), rs.getString("rule_version")), conversationId);
        TopicSegmenterV2.Projection projection = segmenter.segment(rows.stream()
                .map(row -> new TopicSegmenterV2.Message(row.id(), row.seq(), row.role(), row.text()))
                .toList(), persisted);
        Map<Integer, MessageRow> bySeq = new HashMap<>();
        for (MessageRow row : rows) bySeq.put(row.seq(), row);
        List<StoredSegment> existing = jdbc.query("""
                select id, topic_id, start_seq, end_seq, decision_status, source_digest
                from conversation_topic_segment_v2 where conversation_id = ? order by start_seq
                """, (rs, index) -> new StoredSegment(rs.getString("id"), rs.getString("topic_id"),
                rs.getInt("start_seq"), rs.getInt("end_seq"), rs.getString("decision_status"),
                rs.getString("source_digest")), conversationId);
        if (existing.size() > projection.segments().size()) {
            throw drift();
        }
        Map<String, MessageRow> anchors = new HashMap<>();
        for (TopicSegmenterV2.Segment segment : projection.segments()) {
            anchors.putIfAbsent(segment.topicId(), bySeq.get(segment.startSeq()));
        }
        for (Map.Entry<String, MessageRow> entry : anchors.entrySet()) {
            Integer count = jdbc.queryForObject("select count(*) from conversation_topic_v2 where id = ?",
                    Integer.class, entry.getKey());
            if (count == null || count == 0) {
                jdbc.update("""
                        insert into conversation_topic_v2(id, workspace_id, conversation_id,
                            anchor_message_id, anchor_digest, status, rule_version)
                        values (?, ?, ?, ?, ?, 'ACTIVE', ?)
                        """, entry.getKey(), workspaceId, conversationId, entry.getValue().id(),
                        digest(entry.getValue().text()), TopicSegmenterV2.RULE_VERSION);
            }
        }
        for (int index = 0; index < projection.segments().size(); index++) {
            TopicSegmenterV2.Segment segment = projection.segments().get(index);
            String sourceDigest = sourceDigest(rows, segment.startSeq(), segment.endSeq());
            if (index < existing.size()) {
                StoredSegment stored = existing.get(index);
                if (!stored.id().equals(segment.segmentId())
                        || !stored.topicId().equals(segment.topicId())
                        || stored.startSeq() != segment.startSeq()
                        || "STALE".equals(stored.status())
                        || segment.endSeq() < stored.endSeq()) {
                    throw drift();
                }
                if (segment.endSeq() != stored.endSeq()
                        || !segment.status().equals(stored.status())
                        || !sourceDigest.equals(stored.sourceDigest())) {
                    jdbc.update("""
                            update conversation_topic_segment_v2
                            set end_seq = ?, decision_status = ?, decision_reason = ?,
                                source_digest = ?, lock_version = lock_version + 1,
                                updated_at = current_timestamp where id = ?
                            """, segment.endSeq(), segment.status(), segment.reason(),
                            sourceDigest, segment.segmentId());
                }
            } else {
                jdbc.update("""
                        insert into conversation_topic_segment_v2(id, workspace_id, conversation_id,
                            topic_id, start_seq, end_seq, decision_status, decision_reason,
                            rule_version, source_digest)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, segment.segmentId(), workspaceId, conversationId, segment.topicId(),
                        segment.startSeq(), segment.endSeq(), segment.status(), segment.reason(),
                        segment.ruleVersion(), sourceDigest);
            }
        }
        for (ConversationConstraintProjectorV2.Constraint constraint : constraintProjector.project(rows.stream()
                .map(row -> new TopicSegmenterV2.Message(row.id(), row.seq(), row.role(), row.text()))
                .toList())) {
            Integer count = jdbc.queryForObject("select count(*) from conversation_constraint_v2 where id = ?",
                    Integer.class, constraint.id());
            if (count == null || count == 0) {
                jdbc.update("""
                        insert into conversation_constraint_v2(id, workspace_id, conversation_id,
                            source_message_id, kind, scope, constraint_text, valid_from_seq,
                            invalid_after_seq, status, superseded_by, rule_version)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, constraint.id(), workspaceId, conversationId, constraint.sourceMessageId(),
                        constraint.kind(), constraint.scope(), constraint.text(), constraint.validFromSeq(),
                        constraint.invalidAfterSeq(), constraint.status(), constraint.supersededBy(),
                        ConversationConstraintProjectorV2.RULE_VERSION);
            } else {
                jdbc.update("""
                        update conversation_constraint_v2
                        set invalid_after_seq = ?, status = ?, superseded_by = ?
                        where id = ? and workspace_id = ? and conversation_id = ?
                        """, constraint.invalidAfterSeq(), constraint.status(), constraint.supersededBy(),
                        constraint.id(), workspaceId, conversationId);
            }
        }
        summaries.queueBuilds(workspaceId, conversationId);
        return projection;
    }

    private String sourceDigest(List<MessageRow> rows, int start, int end) {
        List<String> refs = new ArrayList<>();
        for (MessageRow row : rows) {
            if (row.seq() >= start && row.seq() <= end) {
                refs.add(row.id() + ":" + row.seq() + ":"
                        + (row.contentHash() == null ? digest(row.text()) : row.contentHash()));
            }
        }
        return digest(String.join("\n", refs));
    }

    private static BusinessException drift() {
        return new BusinessException("CONTEXT_TOPIC_PROJECTION_DRIFT",
                "Existing topic projection no longer matches the message ledger", HttpStatus.CONFLICT);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record MessageRow(String id, int seq, String role, String text,
                              String contentHash, String contextStatus) {}
    private record StoredSegment(String id, String topicId, int startSeq, int endSeq,
                                 String status, String sourceDigest) {}
}
