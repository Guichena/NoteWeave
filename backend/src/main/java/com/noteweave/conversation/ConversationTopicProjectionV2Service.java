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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shadow C1 projection. Published v1 context selection remains unchanged. */
@Service
public class ConversationTopicProjectionV2Service {
    private static final Logger log = LoggerFactory.getLogger(ConversationTopicProjectionV2Service.class);
    /** 约束基线只在本类内部读写：独立 mapper，避免全局 Jackson 命名策略影响持久化格式。 */
    private static final ObjectMapper STATE_MAPPER = new ObjectMapper();
    private static final TypeReference<List<ConstraintOpMerger.Step>> STEP_LIST = new TypeReference<>() {};
    /** 乐观检查失败后的落库重试上限：重读发生在会话行锁内，一次重读即可拿到权威状态。 */
    private static final int CONSTRAINT_APPLY_ATTEMPTS = 3;
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
        // 切分落库与乐观应用都必须是独立事务：默认的 REQUIRED 一旦碰上带事务的调用方，
        // 就会静默把这两段（以及中间的模型调用）并回调用方的事务里，退化成「长事务持会话行锁」。
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
     * <p>
     * 两段事务固定用 {@code PROPAGATION_REQUIRES_NEW}（见 {@link #independentTransaction}），
     * 调用方自带事务也不会把它们并进去。
     */
    private TopicSegmenterV2.Projection refreshAtCutoff(String workspaceId,
                                                        String conversationId, Integer cutoffSeq) {
        return refreshConstraints(workspaceId, conversationId, cutoffSeq).projection();
    }

    /** 仅聊天冻结使用完整性回执；研究入口保持原有调用契约。 */
    public ConstraintRefresh refreshForAnswer(String workspaceId, String conversationId, int cutoffSeq) {
        return refreshConstraints(workspaceId, conversationId, cutoffSeq);
    }

    private ConstraintRefresh refreshConstraints(String workspaceId, String conversationId, Integer cutoffSeq) {
        Segmentation segmentation = null;
        for (int attempt = 0; attempt < CONSTRAINT_APPLY_ATTEMPTS; attempt++) {
            segmentation = Objects.requireNonNull(independentTransaction(conversationId,
                    () -> segment(workspaceId, conversationId, cutoffSeq)));
            ConstraintState observed = segmentation.constraintState();
            boolean rebuild = !usable(observed);
            var extraction = constraintProjection.extract(segmentation.messages(),
                    segmentation.projection().segments(), rebuild ? 0 : observed.processedSeq(),
                    rebuild ? List.of() : observed.steps());
            // 重建不能发布半份新规则；已提交的旧基线和旧行保持原样。
            if (rebuild && extraction.skipped() > 0) {
                return receipt(segmentation, observed);
            }
            Segmentation prepared = segmentation;
            ConstraintApply result = Objects.requireNonNull(independentTransaction(conversationId,
                    () -> applyConstraintsInTransaction(prepared, extraction, rebuild)));
            if (!result.retry()) {
                return receipt(segmentation, result.state());
            }
            log.info("约束上下文已变化，事务外重新准备: conversationId={}, attempt={}",
                    conversationId, attempt + 1);
        }
        log.warn("约束刷新未完成: reason=CONCURRENT_CHANGE, conversationId={}", conversationId);
        return receipt(segmentation, null);
    }

    private boolean usable(ConstraintState state) {
        return state != null && constraintProjection.version().equals(state.ruleVersion())
                && (state.processedSeq() == 0 || !state.steps().isEmpty());
    }

    private ConstraintRefresh receipt(Segmentation segmentation, ConstraintState state) {
        return new ConstraintRefresh(segmentation.projection(),
                state == null ? -1 : state.processedSeq(), state == null ? -1 : state.lockVersion(),
                state == null ? "" : state.ruleVersion());
    }

    /**
     * 调用方已持会话锁；冻结前核对回执，不把落后或另一轮写入伪装成本轮投影。
     * <p>
     * 这里只核对状态一致性，不要求本轮把所有用户消息都抽取成功：抽取模型不可用时投影仍然是一致的，
     * 只是最新几条消息的约束还没进来。把模型可用性当成不一致会让回答整体返回 409，代价远大于收益。
     */
    void requireAnswerConstraints(String workspaceId, String conversationId, ConstraintRefresh refresh) {
        ConstraintState current = latestState(workspaceId, conversationId);
        if (refresh == null || !usable(current)
                || current.processedSeq() != refresh.processedSeq()
                || current.lockVersion() != refresh.lockVersion()
                || !current.ruleVersion().equals(refresh.ruleVersion())
                || !sameTopics(conversationId, refresh.projection().segments())) {
            throw new BusinessException("CONTEXT_CONSTRAINTS_INCOMPLETE",
                    "会话约束尚未完整刷新，请重试", HttpStatus.CONFLICT);
        }
    }

    public record ConstraintRefresh(TopicSegmenterV2.Projection projection,
                                    int processedSeq, int lockVersion, String ruleVersion) {}

    /**
     * 一段必须独立提交的写。
     * <p>
     * 正常走 {@code REQUIRES_NEW}：调用方即使带着事务，写也不会并进去，模型调用更不会被卷进事务。
     * <p>
     * 唯一的例外是「调用方已经在事务里，而那个事务正握着会话行锁」。这时另起事务会在第二条连接上
     * 等同一把 {@code for update} 行锁，直到锁超时（自锁等待，MySQL 默认 50s），比并进调用方事务
     * 还糟。当前只有研究路径是这个情形（{@code ContextV2ShadowSnapshotService.compileResearch}：
     * turn 事务里先 {@code update conversation} 分配 seq，再进到这里），因此这一种情况仍加入外层
     * 事务并打 WARN——研究路径的模型调用依旧跑在外层事务内，要彻底修好得把 compileResearch 移出
     * turn 事务（本批不动研究路径）。
     */
    private <T> T independentTransaction(String conversationId, Supplier<T> body) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("切分/约束落库退化为加入调用方事务（未走 REQUIRES_NEW，避免等外层会话行锁）: "
                    + "conversationId={}", conversationId);
            return body.get();
        }
        return transactionTemplate.execute(status -> body.get());
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

    /** 短事务只验证和提交；依赖变化交回事务外重抽，不在会话锁里调用模型。 */
    private ConstraintApply applyConstraintsInTransaction(Segmentation segmentation,
            ConversationConstraintProjectionV2Service.Extraction extraction, boolean rebuild) {
        String workspaceId = segmentation.workspaceId();
        String conversationId = segmentation.conversationId();
        List<String> locked = jdbc.query("""
                select id from conversation where id = ? and workspace_id = ? for update
                """, (rs, index) -> rs.getString(1), conversationId, workspaceId);
        if (locked.isEmpty()) return new ConstraintApply(null, false);
        Integer invalid = jdbc.queryForObject("""
                select count(*) from conversation_message
                where workspace_id = ? and conversation_id = ? and message_seq <= ?
                  and context_status <> 'CURRENT'
                """, Integer.class, workspaceId, conversationId,
                segmentation.messages().stream().mapToInt(TopicSegmenterV2.Message::seq).max().orElse(0));
        ConstraintState current = latestState(workspaceId, conversationId);
        if (current == null || (invalid != null && invalid > 0)) {
            // 删除后的基线不能被迟到的模型结果重建，原文和派生正文均不得复活。
            return new ConstraintApply(null, false);
        }
        if (!sameTopics(conversationId, segmentation.projection().segments())) {
            return new ConstraintApply(current, true);
        }
        ConstraintState observed = segmentation.constraintState();
        var pending = extraction;
        if (!current.equals(observed)) {
            if (!usable(current) || (rebuild && !"UNBUILT".equals(observed.ruleVersion()))
                    || (!"UNBUILT".equals(observed.ruleVersion())
                        && !current.ruleVersion().equals(observed.ruleVersion()))) {
                return new ConstraintApply(current, true);
            }
            if (current.processedSeq() >= extraction.processedSeq()) {
                return new ConstraintApply(current, false);
            }
            // 比较实际步骤而非只看水位：后缀的 NOOP/targetId 依赖此前 ACTIVE。
            List<ConstraintOpMerger.Step> expected = new ArrayList<>(observed.steps());
            expected.addAll(extraction.steps().stream()
                    .filter(step -> step.seq() <= current.processedSeq()).toList());
            if (!sameSteps(expected, current.steps())) return new ConstraintApply(current, true);
            pending = constraintProjection.refold(segmentation.projection().segments(),
                    current.processedSeq(), current.steps(), extraction.steps());
            rebuild = false;
        }
        if (pending.extracted() == 0 && !rebuild) return new ConstraintApply(current, false);
        List<ConstraintOpMerger.Step> merged = new ArrayList<>(rebuild ? List.of() : current.steps());
        merged.addAll(pending.steps());
        int updated = jdbc.update("""
                update conversation_constraint_state_v2
                set processed_seq = ?, steps_json = ?, rule_version = ?,
                    lock_version = lock_version + 1, updated_at = current_timestamp
                where conversation_id = ? and workspace_id = ?
                  and lock_version = ? and processed_seq = ? and rule_version = ?
                """, pending.processedSeq(), writeSteps(merged), constraintProjection.version(),
                conversationId, workspaceId, current.lockVersion(), current.processedSeq(), current.ruleVersion());
        if (updated != 1) return new ConstraintApply(current, true);
        persistConstraints(workspaceId, conversationId, pending);
        return new ConstraintApply(new ConstraintState(pending.processedSeq(), current.lockVersion() + 1,
                constraintProjection.version(), List.copyOf(merged)), false);
    }

    private boolean sameTopics(String conversationId, List<TopicSegmenterV2.Segment> expected) {
        List<TopicSegmenterV2.Segment> current = jdbc.query("""
                select id, topic_id, start_seq, end_seq, decision_status, decision_reason, rule_version
                from conversation_topic_segment_v2 where conversation_id = ? order by start_seq for update
                """, (rs, index) -> new TopicSegmenterV2.Segment(rs.getString("id"), rs.getString("topic_id"),
                rs.getInt("start_seq"), rs.getInt("end_seq"), rs.getString("decision_status"),
                rs.getString("decision_reason"), rs.getString("rule_version")), conversationId);
        return current.equals(expected);
    }

    private static boolean sameSteps(List<ConstraintOpMerger.Step> expected,
                                     List<ConstraintOpMerger.Step> actual) {
        if (expected.size() != actual.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            var left = expected.get(index);
            var right = actual.get(index);
            // 话题身份已在同一事务按当前切分验证；历史步骤在折叠时按此切分重新定位。
            if (left.seq() != right.seq() || !left.messageId().equals(right.messageId())
                    || !left.ops().equals(right.ops())) return false;
        }
        return true;
    }

    private record ConstraintApply(ConstraintState state, boolean retry) {}

    /** 重读真实已提交基线；不把跨版本状态伪装成水位零。 */
    private ConstraintState latestState(String workspaceId, String conversationId) {
        List<ConstraintState> rows = jdbc.query("""
                select processed_seq, steps_json, rule_version, lock_version
                from conversation_constraint_state_v2
                where conversation_id = ? and workspace_id = ?
                """, (rs, index) -> new ConstraintState(rs.getInt("processed_seq"), rs.getInt("lock_version"),
                rs.getString("rule_version"), readSteps(rs.getString("steps_json"))),
                conversationId, workspaceId);
        if (rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    /** 约束行落库与对账：只处理本轮已处理的区间，快照里没有的旧行删除。 */
    private void persistConstraints(String workspaceId, String conversationId,
                                    ConversationConstraintProjectionV2Service.Extraction extraction) {
        Set<String> snapshotIds = new HashSet<>(extraction.snapshot().size());
        for (ConversationConstraintProjectorV2.Constraint constraint : extraction.snapshot()) {
            snapshotIds.add(constraint.id());
            Integer count = jdbc.queryForObject("select count(*) from conversation_constraint_v2 where id = ?",
                    Integer.class, constraint.id());
            if (count == null || count == 0) {
                jdbc.update("""
                        insert into conversation_constraint_v2(id, workspace_id, conversation_id,
                            source_message_id, kind, scope, constraint_text, valid_from_seq,
                            invalid_after_seq, status, superseded_by, polarity, rule_version)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, constraint.id(), workspaceId, conversationId, constraint.sourceMessageId(),
                        constraint.kind(), constraint.scope(), constraint.text(), constraint.validFromSeq(),
                        constraint.invalidAfterSeq(), constraint.status(), constraint.supersededBy(),
                        constraint.polarity().dbValue(), constraintProjection.version());
            } else {
                // 落库必须与合并器输出逐字段一致：id 只由 (messageId, slot, index) 决定，重抽同一条
                // 消息时取值、槽位（kind）、scope 与极性都可能变，只改状态字段会把旧正文留在库里继续生效。
                jdbc.update("""
                        update conversation_constraint_v2
                        set kind = ?, scope = ?, constraint_text = ?, valid_from_seq = ?,
                            invalid_after_seq = ?, status = ?, superseded_by = ?, polarity = ?,
                            rule_version = ?
                        where id = ? and workspace_id = ? and conversation_id = ?
                        """, constraint.kind(), constraint.scope(), constraint.text(),
                        constraint.validFromSeq(), constraint.invalidAfterSeq(), constraint.status(),
                        constraint.supersededBy(), constraint.polarity().dbValue(),
                        constraintProjection.version(),
                        constraint.id(), workspaceId, conversationId);
            }
        }
        // 合并器每次从基线重算整表：快照里没有的行同样要清掉——重放改成 NOOP/空数组、槽位或下标
        // 变了、旧规则版本留下的行都不会再出现在快照里，但只要还留着 ACTIVE，编译器按库读约束就会
        // 继续把用户已经作废的要求注入回答。真实的撤回/失效行仍由快照带着 REVOKED 落库，历史不丢。
        // 只对账本轮已处理的区间：抽取失败或尚未抽取的消息（valid_from_seq 在 watermark 之后）整行不动。
        List<String> persistedIds = jdbc.queryForList("""
                select id from conversation_constraint_v2
                where workspace_id = ? and conversation_id = ? and valid_from_seq <= ?
                """, String.class, workspaceId, conversationId, extraction.processedSeq());
        for (String persistedId : persistedIds) {
            if (snapshotIds.contains(persistedId)) {
                continue;
            }
            jdbc.update("""
                    delete from conversation_constraint_v2
                    where id = ? and workspace_id = ? and conversation_id = ?
                    """, persistedId, workspaceId, conversationId);
        }
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
            Integer existing = jdbc.queryForObject("""
                    select count(*) from conversation_constraint_v2 where workspace_id = ? and conversation_id = ?
                    """, Integer.class, workspaceId, conversationId);
            String initialVersion = existing != null && existing > 0 ? "UNBUILT" : constraintProjection.version();
            jdbc.update("""
                    insert into conversation_constraint_state_v2(conversation_id, workspace_id,
                        processed_seq, steps_json, rule_version, lock_version)
                    values (?, ?, 0, null, ?, 0)
                    """, conversationId, workspaceId, initialVersion);
            return ConstraintState.empty(initialVersion);
        }
        // 只读已提交状态。过期或损坏基线在事务外重建，成功后与约束行一次提交。
        return rows.get(0);
    }

    private static List<ConstraintOpMerger.Step> readSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return STATE_MAPPER.readValue(json, STEP_LIST);
        } catch (JsonProcessingException ex) {
            // 保留已提交的版本与水位；调用方在事务外从零重建，完整成功后才替换。
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
