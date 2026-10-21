package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.noteweave.conversation.constraint.ConstraintOp;
import com.noteweave.conversation.constraint.ConstraintOpMerger;
import com.noteweave.conversation.constraint.ConstraintOpsExtractor;
import com.noteweave.conversation.constraint.ConstraintOpsPrompt;
import com.noteweave.conversation.constraint.ConstraintScope;
import com.noteweave.conversation.constraint.ConstraintSlot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * REVIEW-d8 阻断项 2：ops 落到 {@code conversation_constraint_v2} 之后必须与 {@code ConstraintOpMerger}
 * 的输出逐字段一致——REVOKE 置无效/撤回、UPDATE 真的改 value 与 scope、NOOP 不变、ADD 新增，
 * 快照里不再出现的行也不能留下 ACTIVE。
 * <p>
 * 不启动 Spring：内存 H2 + 真实合并器，模型用按消息脚本化的 stub。断言里的期望值直接来自
 * {@link ConstraintOpMerger#fold(List)} 对同一批步骤的折叠，与持久化实现无关。
 */
class ConversationConstraintPersistenceFidelityTest {

    private static final String WORKSPACE = "ws-1";
    private static final String CONVERSATION = "conv-1";
    private static final double MIN_CONFIDENCE = 0.55;

    private JdbcTemplate jdbc;
    private StubExtractor extractor;
    private ConversationTopicProjectionV2Service topics;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:constraint-fidelity-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        extractor = new StubExtractor();
        ConversationConstraintProjectionV2Service constraintProjection =
                new ConversationConstraintProjectionV2Service(extractor, new ConstraintOpMerger(MIN_CONFIDENCE));
        topics = new ConversationTopicProjectionV2Service(jdbc,
                mock(ConversationTopicSummaryV2Service.class), null,
                mock(TopicBoundaryReviewService.class), constraintProjection,
                new DataSourceTransactionManager(dataSource));
        jdbc.update("insert into conversation(id, workspace_id) values (?, ?)", CONVERSATION, WORKSPACE);
    }

    /** ADD → UPDATE（带 targetId）→ REVOKE → NOOP 的多轮序列，每轮落库都要与合并器快照逐字段相等。 */
    @Test
    void everyOpPersistsExactlyTheMergerSnapshotAcrossRounds() {
        userMessage("m1", 1, "以后都用英文。");
        respond("m1", add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION));
        refresh();
        assertDatabaseMatchesMerger();
        assertThat(activeTexts()).containsExactly("英文");

        userMessage("m3", 3, "补充：用表格对比。");
        respond("m3", add(ConstraintSlot.TABLE, "format.table", ConstraintScope.THIS_TURN));
        refresh();
        assertDatabaseMatchesMerger();
        assertThat(activeTexts()).containsExactly("英文", "format.table");

        // UPDATE 带 targetId：旧行撤回（superseded_by 指向新行），新行按 op 的取值与 scope 落库。
        userMessage("m5", 5, "更正：改用中文回答。");
        respondWithTarget("m5", ConstraintOp.Op.UPDATE, ConstraintSlot.LANGUAGE, "中文",
                ConstraintScope.CONVERSATION);
        refresh();
        assertDatabaseMatchesMerger();
        assertThat(activeTexts()).containsExactly("format.table", "中文");
        assertThat(statusOf("m1")).isEqualTo("REVOKED");
        assertThat(invalidAfterSeqOf("m1")).isEqualTo(5);
        assertThat(supersededByOf("m1")).isNotEmpty();

        // REVOKE 只撤回目标行，不新增行。
        userMessage("m7", 7, "撤回语言要求。");
        respond("m7", new ConstraintOp(ConstraintOp.Op.REVOKE, ConstraintSlot.LANGUAGE, null, null, null, 0.9));
        refresh();
        assertDatabaseMatchesMerger();
        assertThat(activeTexts()).containsExactly("format.table");
        assertThat(statusOf("m5")).isEqualTo("REVOKED");
        assertThat(invalidAfterSeqOf("m5")).isEqualTo(7);
        assertThat(rowCount()).isEqualTo(3);

        // NOOP：一个字都不许改。
        List<Row> beforeNoop = rows();
        userMessage("m9", 9, "继续。");
        respond("m9", new ConstraintOp(ConstraintOp.Op.NOOP, null, null, null, null,
                ConstraintOp.DEFAULT_CONFIDENCE));
        refresh();
        assertThat(rows()).isEqualTo(beforeNoop);
        assertDatabaseMatchesMerger();

        // 没有新消息：不重写、也不对账删除。
        refresh();
        assertThat(rows()).isEqualTo(beforeNoop);
        assertDatabaseMatchesMerger();
    }

    /** 同一条消息重抽（规则版本升级/基线丢失）时 id 不变，取值与 scope 必须跟着合并器改写。 */
    @Test
    void reExtractionRewritesValueAndScopeOfTheSameRow() {
        userMessage("m1", 1, "以后都用英文。");
        respond("m1", add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION));
        refresh();
        String id = onlyRowId();
        assertThat(row(id).text()).isEqualTo("英文");
        assertThat(row(id).scope()).isEqualTo("CONVERSATION");
        assertThat(row(id).kind()).isEqualTo("LANGUAGE");

        resetExtractionBaseline();
        extractor.steps.clear();
        respond("m1", add(ConstraintSlot.LANGUAGE, "中文", ConstraintScope.THIS_TURN));
        refresh();

        assertThat(onlyRowId()).isEqualTo(id);
        assertThat(row(id).text()).isEqualTo("中文");
        assertThat(row(id).scope()).isEqualTo("CURRENT_TOPIC");
        assertThat(row(id).status()).isEqualTo("ACTIVE");
        assertThat(row(id).invalidAfterSeq()).isNull();
        assertDatabaseMatchesMerger();
    }

    /** 「模型答没有」是终局：重放改成 NOOP 后，这条消息名下的旧行必须消失。 */
    @Test
    void replayAnsweredWithNoopRemovesTheStaleRow() {
        userMessage("m1", 1, "以后都用英文。");
        respond("m1", add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION));
        refresh();
        assertThat(rowCount()).isEqualTo(1);

        resetExtractionBaseline();
        extractor.steps.clear();
        respond("m1", new ConstraintOp(ConstraintOp.Op.NOOP, null, null, null, null,
                ConstraintOp.DEFAULT_CONFIDENCE));
        refresh();

        assertThat(rowCount()).isZero();
        assertDatabaseMatchesMerger();
    }

    /** 空数组与 NOOP 等价：都是「这条消息没有约束」。 */
    @Test
    void replayAnsweredWithEmptyOpsRemovesTheStaleRow() {
        userMessage("m1", 1, "以后都用英文。");
        respond("m1", add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION));
        refresh();
        assertThat(rowCount()).isEqualTo(1);

        resetExtractionBaseline();
        extractor.steps.clear();
        respond("m1");
        refresh();

        assertThat(rowCount()).isZero();
        assertDatabaseMatchesMerger();
    }

    /** 下标参与 id：重抽后 op 顺序变化会让旧 id 全部失效，不能留下两份约束。 */
    @Test
    void reorderedOpsDropTheRowsTheMergerNoLongerEmits() {
        userMessage("m1", 1, "以后都用英文，用表格。");
        respond("m1", add(ConstraintSlot.TABLE, "format.table", ConstraintScope.CONVERSATION),
                add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION));
        refresh();
        assertThat(rowCount()).isEqualTo(2);
        Set<String> before = ids();

        resetExtractionBaseline();
        extractor.steps.clear();
        respond("m1", add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION),
                add(ConstraintSlot.TABLE, "format.table", ConstraintScope.CONVERSATION));
        refresh();

        assertThat(rowCount()).isEqualTo(2);
        assertThat(ids()).doesNotContainAnyElementsOf(before);
        assertDatabaseMatchesMerger();
    }

    /** 抽取停在半路时，watermark 之后的行整行不动；下一轮抽到了再一起对账。 */
    @Test
    void rowsBeyondTheWatermarkStayUntouchedUntilTheirMessageIsExtracted() {
        userMessage("m1", 1, "以后都用英文。");
        userMessage("m3", 3, "补充：用表格对比。");
        jdbc.update("""
                insert into conversation_constraint_v2(id, workspace_id, conversation_id,
                    source_message_id, kind, scope, constraint_text, valid_from_seq,
                    status, rule_version)
                values ('legacy-m3', ?, ?, 'm3', 'FORMAT', 'CURRENT_TOPIC', 'format.table', 3,
                    'ACTIVE', 'topic-segmenter-v2-a1')
                """, WORKSPACE, CONVERSATION);
        respond("m1", add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION));
        extractor.unusable.add("m3");

        refresh();

        assertThat(processedSeq()).isEqualTo(1);
        assertThat(row("legacy-m3").status()).isEqualTo("ACTIVE");
        assertThat(row("legacy-m3").text()).isEqualTo("format.table");
        assertDatabaseMatchesMergerUpTo(1);

        extractor.unusable.remove("m3");
        respond("m3", add(ConstraintSlot.TABLE, "format.table", ConstraintScope.CURRENT_TOPIC));
        refresh();

        assertThat(processedSeq()).isEqualTo(3);
        assertThat(ids()).doesNotContain("legacy-m3");
        assertThat(rowCount()).isEqualTo(2);
        assertDatabaseMatchesMerger();
    }

    // --- 断言与夹具 -------------------------------------------------------

    /**
     * 库里的行必须与合并器对同一批步骤折叠出来的行逐字段相等（含 REVOKED 行）。
     */
    private void assertDatabaseMatchesMerger() {
        assertDatabaseMatchesMergerUpTo(Integer.MAX_VALUE);
    }

    private void assertDatabaseMatchesMergerUpTo(int processedSeq) {
        Comparator<ConversationConstraintProjectorV2.Constraint> byId =
                Comparator.comparing(ConversationConstraintProjectorV2.Constraint::id);
        List<ConversationConstraintProjectorV2.Constraint> expected = new ArrayList<>(
                new ConstraintOpMerger(MIN_CONFIDENCE).fold(extractor.steps));
        expected.removeIf(constraint -> constraint.validFromSeq() > processedSeq);
        expected.sort(byId);
        List<ConversationConstraintProjectorV2.Constraint> actual = jdbc.query("""
                select id, source_message_id, kind, scope, constraint_text, valid_from_seq,
                       invalid_after_seq, status, superseded_by
                from conversation_constraint_v2
                where workspace_id = ? and conversation_id = ? and valid_from_seq <= ?
                """, (rs, index) -> new ConversationConstraintProjectorV2.Constraint(
                        rs.getString("id"), rs.getString("source_message_id"), rs.getString("kind"),
                        rs.getString("scope"), rs.getString("constraint_text"), rs.getInt("valid_from_seq"),
                        rs.getObject("invalid_after_seq") == null ? null : rs.getInt("invalid_after_seq"),
                        rs.getString("status"), rs.getString("superseded_by")),
                WORKSPACE, CONVERSATION, processedSeq);
        actual.sort(byId);
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(jdbc.queryForList("""
                select rule_version from conversation_constraint_v2
                where conversation_id = ? and valid_from_seq <= ?
                """, String.class, CONVERSATION, processedSeq))
                .allMatch(ConversationConstraintProjectionV2Service.VERSION::equals);
    }

    private void respond(String messageId, ConstraintOp... ops) {
        extractor.ops.put(messageId, List.of(ops));
    }

    /** 需要引用上一轮 ACTIVE id 的 op：targetId 在抽取时从提示词里的候选列表取。 */
    private void respondWithTarget(String messageId, ConstraintOp.Op op, ConstraintSlot slot,
                                   String value, ConstraintScope scope) {
        extractor.dynamic.put(messageId, request -> new ConstraintOp(op, slot, value, scope,
                activeId(request, slot), 0.9));
    }

    private static ConstraintOp add(ConstraintSlot slot, String value, ConstraintScope scope) {
        return new ConstraintOp(ConstraintOp.Op.ADD, slot, value, scope, null, 0.9);
    }

    private static String activeId(ConstraintOpsExtractor.Request request, ConstraintSlot slot) {
        return request.active().stream()
                .filter(item -> item.slot() == slot)
                .map(ConstraintOpsExtractor.ActiveConstraint::id)
                .findFirst()
                .orElse(null);
    }

    private void refresh() {
        topics.refresh(WORKSPACE, CONVERSATION);
    }

    /** 让下一次 refresh 认为规则版本过期，从 0 重抽同一条消息（模拟版本升级或基线丢失）。 */
    private void resetExtractionBaseline() {
        jdbc.update("""
                update conversation_constraint_state_v2 set rule_version = 'legacy'
                where conversation_id = ? and workspace_id = ?
                """, CONVERSATION, WORKSPACE);
    }

    private void userMessage(String id, int seq, String text) {
        jdbc.update("""
                insert into conversation_message(id, conversation_id, workspace_id, message_seq,
                    role, content, context_status)
                values (?, ?, ?, ?, 'USER', ?, 'CURRENT')
                """, id, CONVERSATION, WORKSPACE, seq, text);
    }

    private int processedSeq() {
        Number value = jdbc.queryForObject("""
                select processed_seq from conversation_constraint_state_v2 where conversation_id = ?
                """, Number.class, CONVERSATION);
        return value == null ? 0 : value.intValue();
    }

    private int rowCount() {
        Integer value = jdbc.queryForObject("""
                select count(*) from conversation_constraint_v2 where conversation_id = ?
                """, Integer.class, CONVERSATION);
        return value == null ? 0 : value;
    }

    private Set<String> ids() {
        return new HashSet<>(jdbc.queryForList("""
                select id from conversation_constraint_v2 where conversation_id = ?
                """, String.class, CONVERSATION));
    }

    private String onlyRowId() {
        List<String> ids = jdbc.queryForList("""
                select id from conversation_constraint_v2 where conversation_id = ?
                """, String.class, CONVERSATION);
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    private List<Row> rows() {
        return jdbc.query("""
                select id, source_message_id, kind, scope, constraint_text, valid_from_seq,
                       invalid_after_seq, status, superseded_by
                from conversation_constraint_v2 where conversation_id = ? order by id
                """, (rs, index) -> new Row(rs.getString("id"), rs.getString("source_message_id"),
                rs.getString("kind"), rs.getString("scope"), rs.getString("constraint_text"),
                rs.getInt("valid_from_seq"),
                rs.getObject("invalid_after_seq") == null ? null : rs.getInt("invalid_after_seq"),
                rs.getString("status"), rs.getString("superseded_by")), CONVERSATION);
    }

    private Row row(String id) {
        return rows().stream().filter(row -> row.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("missing constraint row " + id));
    }

    private String statusOf(String sourceMessageId) {
        return jdbc.queryForObject("""
                select status from conversation_constraint_v2
                where conversation_id = ? and source_message_id = ?
                """, String.class, CONVERSATION, sourceMessageId);
    }

    private Integer invalidAfterSeqOf(String sourceMessageId) {
        return jdbc.queryForObject("""
                select invalid_after_seq from conversation_constraint_v2
                where conversation_id = ? and source_message_id = ?
                """, Integer.class, CONVERSATION, sourceMessageId);
    }

    private String supersededByOf(String sourceMessageId) {
        return jdbc.queryForObject("""
                select superseded_by from conversation_constraint_v2
                where conversation_id = ? and source_message_id = ?
                """, String.class, CONVERSATION, sourceMessageId);
    }

    private List<String> activeTexts() {
        return jdbc.queryForList("""
                select constraint_text from conversation_constraint_v2
                where conversation_id = ? and status = 'ACTIVE'
                order by valid_from_seq, id
                """, String.class, CONVERSATION);
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
                    context_status varchar(24) default 'CURRENT'
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
    }

    /** 脚本化抽取器：按 messageId 给出 ops 或不可用，并把真正交给合并器的步骤记下来。 */
    private static final class StubExtractor implements ConstraintOpsExtractor {

        final List<ConstraintOpMerger.Step> steps = new ArrayList<>();
        final Map<String, List<ConstraintOp>> ops = new HashMap<>();
        final Map<String, Function<Request, ConstraintOp>> dynamic = new HashMap<>();
        final Set<String> unusable = new HashSet<>();

        @Override
        public Result extract(Request request) {
            if (unusable.contains(request.messageId())) {
                return new Result.Unusable("TIMEOUT");
            }
            List<ConstraintOp> resolved = ops.getOrDefault(request.messageId(), List.of());
            Function<Request, ConstraintOp> dynamicOp = dynamic.get(request.messageId());
            if (dynamicOp != null) {
                resolved = List.of(dynamicOp.apply(request));
            }
            steps.add(new ConstraintOpMerger.Step(request.messageId(), request.seq(),
                    request.topicId(), resolved));
            return new Result.Ok(resolved, ConstraintOpsPrompt.PROMPT_VERSION);
        }
    }

    private record Row(String id, String sourceMessageId, String kind, String scope, String text,
                       int validFromSeq, Integer invalidAfterSeq, String status, String supersededBy) {
    }
}
