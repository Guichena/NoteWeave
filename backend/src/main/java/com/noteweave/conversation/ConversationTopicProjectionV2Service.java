package com.noteweave.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.conversation.constraint.ConstraintOpMerger;
import com.noteweave.retrieval.QueryRewrite;
import com.noteweave.retrieval.provider.EmbeddingClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shadow C1 projection. Published v1 context selection remains unchanged. */
@Service
public class ConversationTopicProjectionV2Service {
    private static final Logger log = LoggerFactory.getLogger(ConversationTopicProjectionV2Service.class);
    /** 约束基线只在本类内部读写：独立 mapper，避免全局 Jackson 命名策略影响持久化格式。 */
    private static final ObjectMapper STATE_MAPPER = new ObjectMapper();
    private static final TypeReference<List<ConstraintOpMerger.Step>> STEP_LIST = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ConversationTopicSummaryV2Service summaries;
    private final EmbeddingClient embeddings;
    private final TopicBoundaryReviewService reviews;
    private final TopicSegmenterV2 segmenter = new TopicSegmenterV2();
    private final ConversationConstraintProjectionV2Service constraintProjection;
    private final TransactionTemplate transactionTemplate;

    public ConversationTopicProjectionV2Service(JdbcTemplate jdbc,
                                                 ConversationTopicSummaryV2Service summaries,
                                                 EmbeddingClient embeddings,
                                                 TopicBoundaryReviewService reviews,
                                                 ConversationConstraintProjectionV2Service constraintProjection,
                                                 PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.summaries = summaries;
        this.embeddings = embeddings;
        this.reviews = reviews;
        this.constraintProjection = constraintProjection;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public TopicSegmenterV2.Projection refresh(String workspaceId, String conversationId) {
        return refreshAtCutoff(workspaceId, conversationId, null);
    }

    /** Freeze the query prefix while the immediately following assistant placeholder is pending. */
    public TopicSegmenterV2.Projection refreshForInputCutoff(String workspaceId,
                                                               String conversationId, int cutoffSeq) {
        return refreshAtCutoff(workspaceId, conversationId, cutoffSeq);
    }

    /**
     * 两段事务：切分与落库在会话行锁里完成并提交，约束抽取在事务外只处理新增消息，
     * 之后用短事务 + 乐观检查（watermark/lock_version 未变）应用 ops。
     * 这样模型调用既不占会话行锁，也不在任何数据库事务里。
     */
    private TopicSegmenterV2.Projection refreshAtCutoff(String workspaceId,
                                                        String conversationId, Integer cutoffSeq) {
        Segmentation segmentation = Objects.requireNonNull(transactionTemplate.execute(
                status -> segment(workspaceId, conversationId, cutoffSeq)));
        applyConstraints(segmentation);
        return segmentation.projection();
    }

    private Segmentation segment(String workspaceId, String conversationId, Integer cutoffSeq) {
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
        List<TopicSegmenterV2.Message> messages = rows.stream()
                .map(row -> new TopicSegmenterV2.Message(row.id(), row.seq(), row.role(), row.text()))
                .toList();
        boolean embed = embeddings != null && embeddings.isEnabled();
        if (embed) {
            // 切分向量始终取用户原话，检索改写的 standalone query 不参与（M5-M08）
            messages = TopicSegmentationVectors.attach(messages, embeddings);
        }
        TopicSegmenterV2.Projection projection = segmenter.segmentSemantic(messages, persisted);
        if (embed && projection.degradationReasons().contains(TopicSegmenterV2.VECTOR_UNAVAILABLE)) {
            log.warn("话题切分退回词项: {}", TopicSegmenterV2.VECTOR_UNAVAILABLE);
        }
        String degradation = projection.degradationReasons().isEmpty()
                ? null : projection.degradationReasons().get(0);
        Map<Integer, MessageRow> bySeq = new HashMap<>();
        for (MessageRow row : rows) bySeq.put(row.seq(), row);
        List<StoredSegment> existing = jdbc.query("""
                select id, topic_id, start_seq, end_seq, decision_status, source_digest, degradation_reason
                from conversation_topic_segment_v2 where conversation_id = ? order by start_seq
                """, (rs, index) -> new StoredSegment(rs.getString("id"), rs.getString("topic_id"),
                rs.getInt("start_seq"), rs.getInt("end_seq"), rs.getString("decision_status"),
                rs.getString("source_digest"), rs.getString("degradation_reason")), conversationId);
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
                        || !sourceDigest.equals(stored.sourceDigest())
                        || !java.util.Objects.equals(degradation, stored.degradationReason())) {
                    jdbc.update("""
                            update conversation_topic_segment_v2
                            set end_seq = ?, decision_status = ?, decision_reason = ?,
                                source_digest = ?, degradation_reason = ?,
                                lock_version = lock_version + 1,
                                updated_at = current_timestamp where id = ?
                            """, segment.endSeq(), segment.status(), segment.reason(),
                            sourceDigest, degradation, segment.segmentId());
                }
            } else {
                jdbc.update("""
                        insert into conversation_topic_segment_v2(id, workspace_id, conversation_id,
                            topic_id, start_seq, end_seq, decision_status, decision_reason,
                            rule_version, source_digest, degradation_reason, boundary_review)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, segment.segmentId(), workspaceId, conversationId, segment.topicId(),
                        segment.startSeq(), segment.endSeq(), segment.status(), segment.reason(),
                        segment.ruleVersion(), sourceDigest, degradation,
                        reviews.initialState(segment.status()));
            }
        }
        // 约束抽取不在这里做：模型调用要等这个事务提交、会话行锁释放之后才发起。
        // 这里只读出增量抽取的进度与重放基线，随切分结果一起交给事务外的抽取阶段。
        ConstraintState constraintState = readConstraintState(workspaceId, conversationId);
        summaries.queueBuilds(workspaceId, conversationId);
        return new Segmentation(workspaceId, conversationId, projection, messages, constraintState);
    }

    /** 模型调用在事务外；只有真的抽到新消息才开短事务落库。 */
    private void applyConstraints(Segmentation segmentation) {
        ConversationConstraintProjectionV2Service.Extraction extraction = constraintProjection.extract(
                segmentation.messages(), segmentation.projection().segments(),
                segmentation.constraintState().processedSeq(), segmentation.constraintState().steps());
        if (extraction.extracted() == 0) {
            return;
        }
        List<ConstraintOpMerger.Step> merged = new ArrayList<>(segmentation.constraintState().steps());
        merged.addAll(extraction.steps());
        String stepsJson = writeSteps(merged);
        Boolean applied = transactionTemplate.execute(status -> applyConstraintsInTransaction(
                segmentation.workspaceId(), segmentation.conversationId(),
                segmentation.constraintState(), extraction, stepsJson));
        if (!Boolean.TRUE.equals(applied)) {
            // 抽取期间另一个 refresh 先写成功：放弃这次结果，下一轮从它推进后的 watermark 重抽。
            log.warn("约束抽取结果被并发刷新覆盖，本轮放弃: conversationId={}, seq={}",
                    segmentation.conversationId(), extraction.processedSeq());
        }
    }

    /**
     * 短事务：先按 watermark 与 lock_version 做乐观检查，检查通过才写约束行。
     * 检查失败返回 false，调用方不再写任何行，watermark 也不前进。
     * <p>
     * 这里重新拿会话行锁（只覆盖几条 upsert，不含模型调用）：消息删除也拿同一把锁并
     * 把约束标 REVOKED/清空正文，不重新加锁的话，删除后本轮 upsert 会把旧行又写回 ACTIVE。
     */
    private Boolean applyConstraintsInTransaction(String workspaceId, String conversationId,
                                                  ConstraintState state,
                                                  ConversationConstraintProjectionV2Service.Extraction extraction,
                                                  String stepsJson) {
        List<String> locked = jdbc.query("""
                select id from conversation where id = ? and workspace_id = ? for update
                """, (rs, index) -> rs.getString(1), conversationId, workspaceId);
        if (locked.isEmpty()) {
            return false;
        }
        int updated = jdbc.update("""
                update conversation_constraint_state_v2
                set processed_seq = ?, steps_json = ?, rule_version = ?,
                    lock_version = lock_version + 1, updated_at = current_timestamp
                where conversation_id = ? and workspace_id = ?
                  and lock_version = ? and processed_seq = ?
                """, extraction.processedSeq(), stepsJson, constraintProjection.version(),
                conversationId, workspaceId, state.lockVersion(), state.processedSeq());
        if (updated != 1) {
            return false;
        }
        for (ConversationConstraintProjectorV2.Constraint constraint : extraction.snapshot()) {
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
                        constraintProjection.version());
            } else {
                // 重放时也把 rule_version 拉齐，否则旧行会一直停在插入时的版本。
                jdbc.update("""
                        update conversation_constraint_v2
                        set invalid_after_seq = ?, status = ?, superseded_by = ?, rule_version = ?
                        where id = ? and workspace_id = ? and conversation_id = ?
                        """, constraint.invalidAfterSeq(), constraint.status(), constraint.supersededBy(),
                        constraintProjection.version(),
                        constraint.id(), workspaceId, conversationId);
            }
        }
        return true;
    }

    /**
     * 读增量抽取的进度。会话行锁已经在这个事务里拿住，缺行时直接补一条即可。
     * 版本升级或基线丢失时从 0 重抽：不能拿旧基线按新规则继续折叠。
     */
    private ConstraintState readConstraintState(String workspaceId, String conversationId) {
        List<ConstraintState> rows = jdbc.query("""
                select processed_seq, steps_json, rule_version, lock_version
                from conversation_constraint_state_v2
                where conversation_id = ? and workspace_id = ?
                """, (rs, index) -> new ConstraintState(rs.getInt("processed_seq"), rs.getInt("lock_version"),
                rs.getString("rule_version"), readSteps(rs.getString("steps_json"))),
                conversationId, workspaceId);
        if (rows.isEmpty()) {
            jdbc.update("""
                    insert into conversation_constraint_state_v2(conversation_id, workspace_id,
                        processed_seq, steps_json, rule_version, lock_version)
                    values (?, ?, 0, null, ?, 0)
                    """, conversationId, workspaceId, constraintProjection.version());
            return ConstraintState.empty(constraintProjection.version());
        }
        ConstraintState state = rows.get(0);
        boolean currentVersion = constraintProjection.version().equals(state.ruleVersion());
        if (!currentVersion || (state.processedSeq() > 0 && state.steps().isEmpty())) {
            jdbc.update("""
                    update conversation_constraint_state_v2
                    set processed_seq = 0, steps_json = null, rule_version = ?,
                        lock_version = lock_version + 1, updated_at = current_timestamp
                    where conversation_id = ? and workspace_id = ?
                    """, constraintProjection.version(), conversationId, workspaceId);
            return new ConstraintState(0, state.lockVersion() + 1, constraintProjection.version(), List.of());
        }
        return state;
    }

    private static List<ConstraintOpMerger.Step> readSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return STATE_MAPPER.readValue(json, STEP_LIST);
        } catch (JsonProcessingException ex) {
            // 基线读不回来就当作没有：readConstraintState 会把 watermark 一并归零重抽。
            log.warn("约束抽取基线无法读取，改为从 0 重抽: {}", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private static String writeSteps(List<ConstraintOpMerger.Step> steps) {
        try {
            return STATE_MAPPER.writeValueAsString(steps);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize constraint steps", ex);
        }
    }

    /** 已落库 answer_run 的检索改写快照：仅用于检索侧诊断与回归校验，话题切分不再消费。 */
    Map<String, String> standaloneQueries(String workspaceId, String conversationId) {
        Map<String, String> queries = new HashMap<>();
        jdbc.query("""
                select query_message_id, retrieval_plan_json
                from answer_run
                where workspace_id = ? and conversation_id = ?
                order by created_at asc, id asc
                """, (RowCallbackHandler) rs -> {
                    String messageId = rs.getString("query_message_id");
                    QueryRewrite.View view = QueryRewrite.viewFromPlanJson(rs.getString("retrieval_plan_json"));
                    if (view == null || view.standaloneQuery().isBlank()) {
                        queries.remove(messageId);
                        return;
                    }
                    queries.put(messageId, view.standaloneQuery());
                }, workspaceId, conversationId);
        return queries;
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
                                 String status, String sourceDigest, String degradationReason) {}

    /** 切分事务的产物：事务外只拿它去调模型，不再回头读库。 */
    private record Segmentation(String workspaceId, String conversationId,
                                TopicSegmenterV2.Projection projection,
                                List<TopicSegmenterV2.Message> messages,
                                ConstraintState constraintState) {}

    /**
     * 增量抽取的进度：{@code processedSeq} 是已处理到的 USER 消息 seq，{@code steps} 是重放基线，
     * {@code lockVersion} 用于短事务里的乐观检查。
     */
    private record ConstraintState(int processedSeq, int lockVersion, String ruleVersion,
                                   List<ConstraintOpMerger.Step> steps) {

        static ConstraintState empty(String ruleVersion) {
            return new ConstraintState(0, 0, ruleVersion, List.of());
        }
    }
}
