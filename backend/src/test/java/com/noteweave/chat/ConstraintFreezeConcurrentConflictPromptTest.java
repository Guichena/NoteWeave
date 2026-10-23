package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.conversation.ContextProjectionV2;
import com.noteweave.conversation.ContextV2RolloutService;
import com.noteweave.conversation.ContextV2ShadowSnapshotService;
import com.noteweave.conversation.ConversationConstraintProjectionV2Service;
import com.noteweave.conversation.ConversationConstraintProjectorV2;
import com.noteweave.conversation.ConversationContextCompilerV2Service;
import com.noteweave.conversation.ConversationTopicProjectionV2Service;
import com.noteweave.conversation.ConversationTopicSummaryV2Service;
import com.noteweave.conversation.TopicBoundaryReviewService;
import com.noteweave.conversation.constraint.ConstraintOp;
import com.noteweave.conversation.constraint.ConstraintOpMerger;
import com.noteweave.conversation.constraint.ConstraintOpsExtractor;
import com.noteweave.conversation.constraint.ConstraintOpsPrompt;
import com.noteweave.conversation.constraint.ConstraintScope;
import com.noteweave.conversation.constraint.ConstraintSlot;
import com.noteweave.memory.MemoryRuntime;
import com.noteweave.memory.MemoryRuntimePack;
import com.noteweave.security.CurrentUserProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 审查阻断①的复现：{@code applyConstraints} 的乐观检查冲突后，{@code freezeAnswer} 仍然按库里
 * 落后于 cutoff 的约束行编译并冻结快照。
 * <p>
 * 场景与审查一致：水位 0，回合 A 的 cutoff 只到「以后都用表格」，回合 B 的 cutoff 还多一条
 * 「这次用中文」。B 的模型调用期间 A 先提交（水位推进到 1，表格约束落库），B 的
 * {@code UPDATE ... processed_seq = 0} 匹配 0 行。修复后 B 在会话行锁内重读水位、把已抽取的
 * ops 重新折叠（<b>不再调用模型</b>）后重试，冻结到快照与回答提示词里的就是「表格 + 中文」。
 * <p>
 * 不启动 Spring：内存 H2 + 最小表结构，模型是脚本化的 stub。
 */
class ConstraintFreezeConcurrentConflictPromptTest {

    private static final String WORKSPACE = "ws-freeze";
    private static final String CONVERSATION = "conv-freeze";
    private static final String ACTOR = "actor-freeze";
    private static final String ANSWER_RUN = "run-freeze";
    private static final String QUERY_MESSAGE = "m3";
    private static final int CUTOFF = 3;
    private static final double MIN_CONFIDENCE = 0.55;

    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private StubExtractor extractor;
    private ContextV2ShadowSnapshotService snapshots;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:constraint-freeze-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper();
        createSchema();
        extractor = new StubExtractor();

        ConversationTopicSummaryV2Service summaries = mock(ConversationTopicSummaryV2Service.class);
        when(summaries.ready(anyString(), anyString(), anyInt())).thenReturn(List.of());
        MemoryRuntime memory = mock(MemoryRuntime.class);
        when(memory.recall(any())).thenReturn(MemoryRuntimePack.empty());
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        when(users.requireUserId()).thenReturn(ACTOR);
        ContextV2RolloutService rollout = mock(ContextV2RolloutService.class);
        when(rollout.shadowEnabled(anyString())).thenReturn(true);

        ConversationTopicProjectionV2Service topics = new ConversationTopicProjectionV2Service(jdbc,
                summaries, null, mock(TopicBoundaryReviewService.class),
                new ConversationConstraintProjectionV2Service(extractor, MIN_CONFIDENCE),
                new DataSourceTransactionManager(dataSource));
        ConversationContextCompilerV2Service compiler = new ConversationContextCompilerV2Service(jdbc,
                mock(WorkspaceAccessGuard.class), users, memory, summaries);
        var transactionManager = new DataSourceTransactionManager(dataSource);
        var proxy = new org.springframework.aop.framework.ProxyFactory(new ContextV2ShadowSnapshotService(
                jdbc, mapper, topics, compiler, rollout, transactionManager, false));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                transactionManager, new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        snapshots = (ContextV2ShadowSnapshotService) proxy.getProxy();

        jdbc.update("insert into conversation(id, workspace_id) values (?, ?)", CONVERSATION, WORKSPACE);
    }

    @Test
    void freezeUsesConstraintsExtractedInThisTurnEvenWhenAnotherRefreshWinsTheOptimisticCheck() {
        userMessage("m1", 1, "以后都用英文。");
        assistantMessage("m2", 2, "好的。");
        userMessage(QUERY_MESSAGE, CUTOFF, "这次用中文。");
        pendingAssistant("m4", CUTOFF + 1, QUERY_MESSAGE);
        jdbc.update("""
                insert into answer_run(id, workspace_id, conversation_id, query_message_id)
                values (?, ?, ?, ?)
                """, ANSWER_RUN, WORKSPACE, CONVERSATION, QUERY_MESSAGE);
        extractor.ops.put("m1", table());
        extractor.ops.put(QUERY_MESSAGE, language("中文"));
        // B 在等模型时，A（cutoff 只到 m1）先提交：水位推进到 1，它的表格约束落库。
        extractor.onCall = request -> {
            if ("m1".equals(request.messageId())) {
                winnerApplied(1, "m1", new ConstraintOp(ConstraintOp.Op.ADD, ConstraintSlot.TABLE,
                        "format.table", ConstraintScope.CONVERSATION, null, 0.9));
            }
        };

        snapshots.freezeAnswer(WORKSPACE, ACTOR, CONVERSATION, QUERY_MESSAGE, ANSWER_RUN,
                "这次用中文。", "QA");

        // 冲突后不重抽：每条消息的模型只调一次，重试用的是本轮已经拿到的 ops。
        assertThat(extractor.messageIds).containsExactly("m1", QUERY_MESSAGE);
        // 库里的水位与基线都推进到本轮 cutoff，胜者的约束与本轮的「中文」同时生效。
        assertThat(processedSeq()).isEqualTo(CUTOFF);
        assertThat(stepsJson()).contains("m1").contains(QUERY_MESSAGE);

        ContextProjectionV2 frozen = frozenProjection();
        assertThat(frozen.cutoffSeq()).isEqualTo(CUTOFF);
        assertThat(frozen.constraints()).extracting(ContextProjectionV2.UserConstraint::text)
                .containsExactlyInAnyOrder("format.table", "中文");
        // 冻结下来的回答提示词用的就是这两条约束。
        String prompt = ChatService.renderChatControlSection(null,
                new ContextV2ShadowSnapshotService.FrozenAnswer("snapshot", "snapshot-sha", frozen));
        assertThat(prompt)
                .contains("## 当前有效的用户约束（冻结会话）")
                .contains("- format.table")
                .contains("- 中文");
    }

    /**
     * 抽取模型不可用只说明最新这条消息的约束还没进来，不能让整轮回答失败。
     * 冻结用的是已提交的一致状态，水位不覆盖最新 USER 是降级，不是数据不一致。
     */
    @Test
    void unavailableExtractionStillFreezesWithKnownConstraints() {
        userMessage(QUERY_MESSAGE, 1, "以后用中文。");
        pendingAssistant("m2", 2, QUERY_MESSAGE);
        jdbc.update("insert into answer_run(id, workspace_id, conversation_id, query_message_id) values (?, ?, ?, ?)",
                ANSWER_RUN, WORKSPACE, CONVERSATION, QUERY_MESSAGE);

        snapshots.freezeAnswer(WORKSPACE, ACTOR, CONVERSATION, QUERY_MESSAGE, ANSWER_RUN, "以后用中文。", "QA");

        assertThat(frozenProjection().constraints()).isEmpty();
        // 已冻结的 READY 不重写。
        String original = jdbc.queryForObject("select projection_sha256 from context_v2_shadow_snapshot", String.class);
        snapshots.freezeAnswer(WORKSPACE, ACTOR, CONVERSATION, QUERY_MESSAGE, ANSWER_RUN, "以后用中文。", "QA");
        assertThat(jdbc.queryForObject("select projection_sha256 from context_v2_shadow_snapshot", String.class))
                .isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(*) from context_v2_shadow_snapshot", Integer.class)).isEqualTo(1);
    }

    /** 失败回执可以被成功重试替换，最终每个 answer_run 只有一行。 */
    @Test
    void failedReceiptCanBeReplacedByARetry() {
        userMessage(QUERY_MESSAGE, 1, "以后用中文。");
        pendingAssistant("m2", 2, QUERY_MESSAGE);
        jdbc.update("insert into answer_run(id, workspace_id, conversation_id, query_message_id) values (?, ?, ?, ?)",
                ANSWER_RUN, WORKSPACE, CONVERSATION, QUERY_MESSAGE);
        snapshots.recordFailure(WORKSPACE, CONVERSATION, QUERY_MESSAGE, ANSWER_RUN, "CONTEXT_BUDGET_EXCEEDED");
        extractor.ops.put(QUERY_MESSAGE, language("中文"));

        snapshots.freezeAnswer(WORKSPACE, ACTOR, CONVERSATION, QUERY_MESSAGE, ANSWER_RUN, "以后用中文。", "QA");

        assertThat(frozenProjection().constraints()).extracting(ContextProjectionV2.UserConstraint::text)
                .containsExactly("中文");
        assertThat(jdbc.queryForObject("select count(*) from context_v2_shadow_snapshot", Integer.class)).isEqualTo(1);
    }

    /** 冻结快照：断言状态是 READY，再按 answer_run 读库里的 projection_json。 */
    private ContextProjectionV2 frozenProjection() {
        List<String> statuses = jdbc.queryForList("""
                select status from context_v2_shadow_snapshot where answer_run_id = ?
                """, String.class, ANSWER_RUN);
        assertThat(statuses).containsExactly("READY");
        String json = jdbc.queryForObject("""
                select projection_json from context_v2_shadow_snapshot where answer_run_id = ?
                """, String.class, ANSWER_RUN);
        try {
            return mapper.readValue(json, ContextProjectionV2.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 模拟「另一个 refresh 先提交」：水位与基线写进库，约束行按合并结果落 ACTIVE。 */
    private void winnerApplied(int processedSeq, String messageId, ConstraintOp op) {
        ConstraintOpMerger.Step step = new ConstraintOpMerger.Step(messageId, processedSeq, "", List.of(op));
        jdbc.update("""
                update conversation_constraint_state_v2
                set processed_seq = ?, steps_json = ?, lock_version = lock_version + 1
                where conversation_id = ?
                """, processedSeq, stepsJson(List.of(step)), CONVERSATION);
        for (ConversationConstraintProjectorV2.Constraint row
                : new ConstraintOpMerger(MIN_CONFIDENCE).fold(List.of(step))) {
            jdbc.update("""
                    insert into conversation_constraint_v2(id, workspace_id, conversation_id,
                        source_message_id, kind, scope, constraint_text, valid_from_seq,
                        invalid_after_seq, status, superseded_by, polarity, rule_version)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, row.id(), WORKSPACE, CONVERSATION, row.sourceMessageId(), row.kind(),
                    row.scope(), row.text(), row.validFromSeq(), row.invalidAfterSeq(), row.status(),
                    row.supersededBy(), row.polarity().dbValue(),
                    ConversationConstraintProjectionV2Service.VERSION);
        }
    }

    private static List<ConstraintOp> language(String value) {
        return List.of(new ConstraintOp(ConstraintOp.Op.ADD, ConstraintSlot.LANGUAGE, value,
                ConstraintScope.CONVERSATION, null, 0.9));
    }

    private static List<ConstraintOp> table() {
        return List.of(new ConstraintOp(ConstraintOp.Op.ADD, ConstraintSlot.TABLE, "format.table",
                ConstraintScope.CONVERSATION, null, 0.9));
    }

    private String stepsJson(List<ConstraintOpMerger.Step> steps) {
        try {
            return mapper.writeValueAsString(steps);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int processedSeq() {
        Number value = jdbc.queryForObject("""
                select processed_seq from conversation_constraint_state_v2 where conversation_id = ?
                """, Number.class, CONVERSATION);
        return value == null ? 0 : value.intValue();
    }

    private String stepsJson() {
        return jdbc.queryForObject("""
                select steps_json from conversation_constraint_state_v2 where conversation_id = ?
                """, String.class, CONVERSATION);
    }

    private void userMessage(String id, int seq, String text) {
        insertMessage(id, seq, "USER", text, "CURRENT", null);
    }

    private void assistantMessage(String id, int seq, String text) {
        insertMessage(id, seq, "ASSISTANT", text, "CURRENT", null);
    }

    private void pendingAssistant(String id, int seq, String replyTo) {
        insertMessage(id, seq, "ASSISTANT", "", "PENDING", replyTo);
    }

    private void insertMessage(String id, int seq, String role, String text,
                               String status, String replyTo) {
        jdbc.update("""
                insert into conversation_message(id, conversation_id, workspace_id, message_seq,
                    role, content, context_status, reply_to_message_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, CONVERSATION, WORKSPACE, seq, role, text, status, replyTo);
    }

    private void createSchema() {
        jdbc.execute("create table conversation (id varchar(36) primary key, workspace_id varchar(36) not null)");
        jdbc.execute("""
                create table conversation_message (
                    id varchar(36) primary key,
                    conversation_id varchar(36) not null,
                    workspace_id varchar(36) not null,
                    message_seq int not null,
                    role varchar(32) not null,
                    content longtext,
                    content_hash char(64),
                    context_status varchar(24) default 'CURRENT',
                    reply_to_message_id varchar(36)
                )
                """);
        jdbc.execute("""
                create table conversation_topic_v2 (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    conversation_id varchar(36) not null,
                    anchor_message_id varchar(36) not null,
                    anchor_digest char(64) not null,
                    status varchar(24) not null,
                    rule_version varchar(64) not null
                )
                """);
        jdbc.execute("""
                create table conversation_topic_segment_v2 (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    conversation_id varchar(36) not null,
                    topic_id varchar(36) not null,
                    start_seq int not null,
                    end_seq int not null,
                    decision_status varchar(24) not null,
                    decision_reason varchar(80),
                    rule_version varchar(64),
                    source_digest char(64),
                    degradation_reason varchar(80),
                    boundary_review varchar(24),
                    lock_version int not null default 0,
                    updated_at timestamp not null default current_timestamp
                )
                """);
        jdbc.execute("""
                create table conversation_constraint_v2 (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    conversation_id varchar(36) not null,
                    source_message_id varchar(36) not null,
                    kind varchar(32) not null,
                    scope varchar(64) not null,
                    constraint_text longtext not null,
                    valid_from_seq int not null,
                    invalid_after_seq int,
                    status varchar(24) not null,
                    superseded_by varchar(36),
                    polarity varchar(16) not null default 'REQUIRE',
                    rule_version varchar(64) not null
                )
                """);
        jdbc.execute("""
                create table conversation_constraint_state_v2 (
                    conversation_id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    processed_seq int not null default 0,
                    steps_json longtext,
                    rule_version varchar(64) not null,
                    lock_version int not null default 0,
                    updated_at timestamp not null default current_timestamp
                )
                """);
        jdbc.execute("""
                create table answer_run (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    conversation_id varchar(36) not null,
                    query_message_id varchar(36) not null
                )
                """);
        jdbc.execute("""
                create table context_v2_shadow_snapshot (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    answer_run_id varchar(36) not null,
                    conversation_id varchar(36) not null,
                    query_message_id varchar(36) not null,
                    cutoff_seq int not null,
                    status varchar(24) not null,
                    projection_json longtext,
                    projection_sha256 char(64),
                    failure_code varchar(80),
                    unique(answer_run_id)
                )
                """);
        jdbc.execute("""
                create table context_v2_shadow_ref (
                    snapshot_id varchar(36) not null,
                    ref_type varchar(32) not null,
                    ref_id varchar(36) not null
                )
                """);
    }

    /** 脚本化抽取器：按 messageId 给出预置 ops；没有预置就当作抽取不可用。 */
    private static final class StubExtractor implements ConstraintOpsExtractor {

        final Map<String, List<ConstraintOp>> ops = new HashMap<>();
        final List<String> messageIds = new ArrayList<>();
        Consumer<Request> onCall = request -> { };

        @Override
        public Result extract(Request request) {
            messageIds.add(request.messageId());
            onCall.accept(request);
            List<ConstraintOp> resolved = ops.get(request.messageId());
            if (resolved == null) {
                return new Result.Unusable("NO_STUB");
            }
            return new Result.Ok(resolved, ConstraintOpsPrompt.PROMPT_VERSION);
        }
    }
}
