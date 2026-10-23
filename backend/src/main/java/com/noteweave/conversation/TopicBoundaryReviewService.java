package com.noteweave.conversation;

import com.noteweave.chat.ChatLlmClient;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 异步复核不确定的话题边界。只改话题归属和状态，不删除分段，也不缩短结束位置。
 * 模型不可用时保持 UNCERTAIN。
 */
@Service
public class TopicBoundaryReviewService {
    static final String SAME = "MODEL_SAME_TOPIC";
    static final String NEW_TOPIC = "MODEL_NEW_TOPIC";
    static final String RETURN = "MODEL_TOPIC_RETURN";
    private static final Logger log = LoggerFactory.getLogger(TopicBoundaryReviewService.class);

    private final JdbcTemplate jdbc;
    private final ChatLlmClient llmClient;
    private final boolean enabled;

    public TopicBoundaryReviewService(
            JdbcTemplate jdbc,
            ChatLlmClient llmClient,
            @Value("${noteweave.conversation.boundary-review-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.llmClient = llmClient;
        this.enabled = enabled;
    }

    /** 新写入的段使用的复核状态。确定的段留空；不确定且暂时不能复核时直接记 NOT_RUN。 */
    public String initialState(String status) {
        if (!"UNCERTAIN".equals(status)) {
            return null;
        }
        if (enabled && llmClient != null && llmClient.isEnabled()) {
            return "PENDING";
        }
        return "NOT_RUN";
    }

    @Scheduled(
            initialDelayString = "${noteweave.conversation.boundary-review-delay-ms:30000}",
            fixedDelayString = "${noteweave.conversation.boundary-review-delay-ms:30000}"
    )
    public void scheduled() {
        try {
            reviewPending();
        } catch (RuntimeException ex) {
            // 只记异常类型：正文可能带上游响应片段，不写进日志。
            log.warn("话题边界复核失败: type={}", ex.getClass().getSimpleName());
        }
    }

    public int reviewPending() {
        return review(null);
    }

    /** 测试只复核一条会话，避免碰到同一库里的其他待复核段。 */
    int reviewConversation(String conversationId) {
        return review(conversationId);
    }

    private int review(String conversationId) {
        if (!enabled) {
            return 0;
        }
        String sql = """
                select id, workspace_id, conversation_id, topic_id, start_seq, end_seq, degradation_reason
                from conversation_topic_segment_v2
                where decision_status = 'UNCERTAIN' and boundary_review = 'PENDING'
                """ + (conversationId == null ? "" : " and conversation_id = ? ") + """
                order by created_at, id
                limit 20
                """;
        List<Row> rows = conversationId == null
                ? jdbc.query(sql, (rs, index) -> row(rs))
                : jdbc.query(sql, (rs, index) -> row(rs), conversationId);
        int updated = 0;
        for (Row row : rows) {
            if (llmClient == null || !llmClient.isEnabled()) {
                updated += markNotRun(row);
                continue;
            }
            TopicBoundaryJudge.Decision decision = TopicBoundaryJudge.judge(prompt(row), llmClient);
            updated += decision.skipped() ? markNotRun(row) : apply(row, decision);
        }
        return updated;
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getString("id"), rs.getString("workspace_id"),
                rs.getString("conversation_id"), rs.getString("topic_id"),
                rs.getInt("start_seq"), rs.getInt("end_seq"), rs.getString("degradation_reason"));
    }

    private int apply(Row row, TopicBoundaryJudge.Decision decision) {
        return switch (decision.decision()) {
            case "NEW" -> markReviewed(row, row.topicId(), NEW_TOPIC);
            case "SAME" -> {
                String previous = previousTopic(row);
                yield previous == null ? markNotRun(row) : markReviewed(row, previous, SAME);
            }
            case "RETURN" -> {
                List<String> topics = earlierTopics(row);
                int index = decision.topicIndex() == null ? -1 : decision.topicIndex();
                yield index < 0 || index >= topics.size()
                        ? markNotRun(row)
                        : markReviewed(row, topics.get(index), RETURN);
            }
            default -> markNotRun(row);
        };
    }

    private String prompt(Row row) {
        List<String> current = jdbc.query("""
                select content from conversation_message
                where conversation_id = ? and workspace_id = ?
                  and message_seq between ? and ? and role = 'USER' and context_status = 'CURRENT'
                order by message_seq
                """, (rs, index) -> rs.getString(1),
                row.conversationId(), row.workspaceId(), row.startSeq(), row.endSeq());
        List<String> earlier = earlierTopics(row);
        StringBuilder user = new StringBuilder();
        user.append("更早话题序号：\n");
        if (earlier.isEmpty()) {
            user.append("（无）\n");
        }
        for (int index = 0; index < earlier.size(); index++) {
            user.append(index).append(". ").append(topicAnchor(row, earlier.get(index))).append('\n');
        }
        String previous = previousTopic(row);
        user.append("紧邻上一段话题：").append(previous == null ? "（无）" : topicAnchor(row, previous)).append('\n');
        user.append("当前不确定段的用户消息：\n");
        for (String text : current) {
            user.append(text).append('\n');
        }
        return user.toString();
    }

    private String topicAnchor(Row row, String topicId) {
        String anchor = jdbc.query("""
                select m.content from conversation_topic_v2 t
                join conversation_message m on m.id = t.anchor_message_id
                where t.id = ? and t.conversation_id = ?
                """, rs -> rs.next() ? rs.getString(1) : "", topicId, row.conversationId());
        if (anchor == null || anchor.isBlank()) {
            return topicId;
        }
        String compact = anchor.replace('\n', ' ').trim();
        return compact.length() <= 80 ? compact : compact.substring(0, 80);
    }

    private String previousTopic(Row row) {
        return jdbc.query("""
                select topic_id from conversation_topic_segment_v2
                where conversation_id = ? and workspace_id = ?
                  and decision_status <> 'STALE' and end_seq < ?
                order by end_seq desc
                limit 1
                """, rs -> rs.next() ? rs.getString(1) : null,
                row.conversationId(), row.workspaceId(), row.startSeq());
    }

    private List<String> earlierTopics(Row row) {
        return jdbc.query("""
                select topic_id from conversation_topic_segment_v2
                where conversation_id = ? and workspace_id = ?
                  and decision_status <> 'STALE' and start_seq < ? and topic_id <> ?
                group by topic_id
                order by min(start_seq)
                """, (rs, index) -> rs.getString(1),
                row.conversationId(), row.workspaceId(), row.startSeq(), row.topicId());
    }

    private int markReviewed(Row row, String topicId, String reason) {
        return jdbc.update("""
                update conversation_topic_segment_v2
                set topic_id = ?, decision_status = 'CONFIDENT', decision_reason = ?,
                    boundary_review = 'REVIEWED', lock_version = lock_version + 1,
                    updated_at = current_timestamp
                where id = ? and decision_status = 'UNCERTAIN' and boundary_review = 'PENDING'
                """, topicId, reason, row.id());
    }

    private int markNotRun(Row row) {
        String degradation = TopicSegmenterV2.VECTOR_UNAVAILABLE.equals(row.degradationReason())
                ? row.degradationReason()
                : TopicBoundaryJudge.NOT_RUN;
        return jdbc.update("""
                update conversation_topic_segment_v2
                set boundary_review = 'NOT_RUN', decision_reason = ?, degradation_reason = ?,
                    lock_version = lock_version + 1, updated_at = current_timestamp
                where id = ? and decision_status = 'UNCERTAIN' and boundary_review = 'PENDING'
                """, TopicBoundaryJudge.NOT_RUN, degradation, row.id());
    }

    private record Row(String id, String workspaceId, String conversationId, String topicId,
                       int startSeq, int endSeq, String degradationReason) {
    }
}
