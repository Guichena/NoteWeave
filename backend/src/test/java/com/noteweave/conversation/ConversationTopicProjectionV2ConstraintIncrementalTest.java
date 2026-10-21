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
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 「只抽新增消息 + 模型调用在事务外 + 短事务乐观检查」的接线测试。
 * <p>
 * 用内存 H2 起最小的几张表直接构造服务，不启动 Spring 上下文，模型用 stub。
 * 覆盖审查阻断项里的三点：重复调用不再重抽旧消息、模型调用期间没有活动事务、
 * 抽取期间状态被并发改写时一条约束都不写且下次重抽。
 */
class ConversationTopicProjectionV2ConstraintIncrementalTest {

    private static final String WORKSPACE = "ws-1";
    private static final String CONVERSATION = "conv-1";

    private JdbcTemplate jdbc;
    private StubExtractor extractor;
    private ConversationTopicProjectionV2Service topics;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:constraint-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        extractor = new StubExtractor();
        ConversationConstraintProjectionV2Service constraintProjection =
                new ConversationConstraintProjectionV2Service(extractor, new ConstraintOpMerger(0.55));
        topics = new ConversationTopicProjectionV2Service(jdbc,
                mock(ConversationTopicSummaryV2Service.class), null,
                mock(TopicBoundaryReviewService.class), constraintProjection,
                new DataSourceTransactionManager(dataSource));
        jdbc.update("insert into conversation(id, workspace_id) values (?, ?)", CONVERSATION, WORKSPACE);
    }

    @Test
    void repeatedRefreshDoesNotReExtractOldMessages() {
        userMessage("m1", 1, "以后都用表格。");
        assistantMessage("m2", 2, "好的");
        userMessage("m3", 3, "先列三点。");

        topics.refresh(WORKSPACE, CONVERSATION);

        assertThat(extractor.messageIds).containsExactly("m1", "m3");
        assertThat(processedSeq()).isEqualTo(3);
        // 两条同槽位同值的 ADD 只落一行，但两步都记进了基线。
        assertThat(constraintIds()).hasSize(1);

        topics.refresh(WORKSPACE, CONVERSATION);

        // 没有新的 USER 消息：不再调模型，已落库的约束也不重写。
        assertThat(extractor.messageIds).containsExactly("m1", "m3");
        assertThat(processedSeq()).isEqualTo(3);
    }

    @Test
    void modelCallsRunOutsideAnyDatabaseTransaction() {
        userMessage("m1", 1, "以后都用表格。");
        userMessage("m3", 3, "先列三点。");

        topics.refresh(WORKSPACE, CONVERSATION);

        // 抽取阶段不持有切分会话行锁的那个事务。
        assertThat(extractor.transactionActive).containsExactly(false, false);
        assertThat(extractor.messageIds).hasSize(2);
    }

    @Test
    void concurrentStateChangeAbandonsTheExtractionWithoutWriting() {
        userMessage("m1", 1, "以后都用表格。");
        extractor.onCall = request -> jdbc.update("""
                update conversation_constraint_state_v2
                set lock_version = lock_version + 1
                where conversation_id = ?
                """, CONVERSATION);

        topics.refresh(WORKSPACE, CONVERSATION);

        // 乐观检查失败：约束一行都不写，watermark 停在 0，基线与状态留给下一轮。
        assertThat(constraintIds()).isEmpty();
        assertThat(processedSeq()).isZero();
        assertThat(lockVersion()).isEqualTo(1);

        extractor.onCall = request -> { };
        topics.refresh(WORKSPACE, CONVERSATION);

        // 下一次重抽成功：这次状态没被改写，结果正常落库。
        assertThat(extractor.messageIds).containsExactly("m1", "m1");
        assertThat(constraintIds()).hasSize(1);
        assertThat(processedSeq()).isEqualTo(1);
    }

    @Test
    void unavailableModelStopsTheWatermarkAndRetriesOnlyThatMessage() {
        userMessage("m1", 1, "以后都用表格。");
        userMessage("m3", 3, "以后用中文。");
        extractor.responder = request -> "m3".equals(request.messageId())
                ? new ConstraintOpsExtractor.Result.Unusable("DISABLED")
                : tableConstraint();

        topics.refresh(WORKSPACE, CONVERSATION);

        // 失败处停住：m3 没抽到，watermark 只推进到 m1。
        assertThat(extractor.messageIds).containsExactly("m1", "m3");
        assertThat(processedSeq()).isEqualTo(1);
        assertThat(constraintIds()).hasSize(1);

        extractor.responder = request -> new ConstraintOpsExtractor.Result.Ok(List.of(new ConstraintOp(
                ConstraintOp.Op.ADD, ConstraintSlot.LANGUAGE, "中文", ConstraintScope.CONVERSATION,
                null, 0.9)), ConstraintOpsPrompt.PROMPT_VERSION);
        topics.refresh(WORKSPACE, CONVERSATION);

        // 只重抽没抽到的那条，已经处理过的 m1 不再重放。
        assertThat(extractor.messageIds).containsExactly("m1", "m3", "m3");
        assertThat(processedSeq()).isEqualTo(3);
        assertThat(constraintIds()).hasSize(2);
    }

    private static ConstraintOpsExtractor.Result tableConstraint() {
        return new ConstraintOpsExtractor.Result.Ok(List.of(new ConstraintOp(ConstraintOp.Op.ADD,
                ConstraintSlot.TABLE, "format.table", ConstraintScope.CONVERSATION, null, 0.9)),
                ConstraintOpsPrompt.PROMPT_VERSION);
    }

    private void userMessage(String id, int seq, String text) {
        insertMessage(id, seq, "USER", text);
    }

    private void assistantMessage(String id, int seq, String text) {
        insertMessage(id, seq, "ASSISTANT", text);
    }

    private void insertMessage(String id, int seq, String role, String text) {
        jdbc.update("""
                insert into conversation_message(id, conversation_id, workspace_id, message_seq,
                    role, content, context_status)
                values (?, ?, ?, ?, ?, ?, 'CURRENT')
                """, id, CONVERSATION, WORKSPACE, seq, role, text);
    }

    private int processedSeq() {
        return stateNumber("processed_seq");
    }

    private int lockVersion() {
        return stateNumber("lock_version");
    }

    private int stateNumber(String column) {
        Number value = jdbc.queryForObject("select " + column
                        + " from conversation_constraint_state_v2 where conversation_id = ?",
                Number.class, CONVERSATION);
        return value == null ? 0 : value.intValue();
    }

    private List<String> constraintIds() {
        return jdbc.queryForList("""
                select id from conversation_constraint_v2 where conversation_id = ? order by id
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

    /** 脚本化抽取器：记录调用顺序与调用时是否有活动事务，返回值可按用例替换。 */
    private static final class StubExtractor implements ConstraintOpsExtractor {

        final List<String> messageIds = new ArrayList<>();
        final List<Boolean> transactionActive = new ArrayList<>();
        Consumer<Request> onCall = request -> { };
        Function<Request, Result> responder = request -> tableConstraint();

        @Override
        public Result extract(Request request) {
            messageIds.add(request.messageId());
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            onCall.accept(request);
            return responder.apply(request);
        }
    }
}
