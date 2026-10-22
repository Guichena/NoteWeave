package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.conversation.constraint.ConstraintIds;
import com.noteweave.conversation.constraint.ConstraintOp;
import com.noteweave.conversation.constraint.ConstraintOpMerger;
import com.noteweave.conversation.constraint.ConstraintOpsExtractor;
import com.noteweave.conversation.constraint.ConstraintOpsPrompt;
import com.noteweave.conversation.constraint.ConstraintPolarity;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 「只抽新增消息 + 模型调用在事务外 + 短事务乐观检查」的接线测试。
 * <p>
 * 用内存 H2 起最小的几张表直接构造服务，不启动 Spring 上下文，模型用 stub。
 * 覆盖审查阻断项里的三点：重复调用不再重抽旧消息、模型调用期间没有活动事务、
 * 抽取期间状态被并发改写时重新读取水位并重放（不重复调用模型）、进度行被删除时才放弃；
 * 另外钉住两段事务的 REQUIRES_NEW 传播行为，以及调用方自带事务（研究路径）时不再另开连接等外层行锁。
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
        topics = projectionService(new DataSourceTransactionManager(dataSource));
        jdbc.update("insert into conversation(id, workspace_id) values (?, ?)", CONVERSATION, WORKSPACE);
    }

    private ConversationTopicProjectionV2Service projectionService(PlatformTransactionManager manager) {
        ConversationConstraintProjectionV2Service constraintProjection =
                new ConversationConstraintProjectionV2Service(extractor, new ConstraintOpMerger(0.55));
        return new ConversationTopicProjectionV2Service(jdbc,
                mock(ConversationTopicSummaryV2Service.class), null,
                mock(TopicBoundaryReviewService.class), constraintProjection, manager);
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
    void segmentationAndApplyOpenIndependentTransactions() {
        userMessage("m1", 1, "以后都用表格。");
        RecordingTransactionManager recorder = new RecordingTransactionManager(jdbc.getDataSource());

        projectionService(recorder).refresh(WORKSPACE, CONVERSATION);

        // 切分落库与乐观应用都显式声明 REQUIRES_NEW：即使将来有调用方带事务，
        // 这两段也不会被并进去，模型调用更不会被卷进调用方的事务。
        assertThat(recorder.propagations).containsExactly(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(extractor.transactionActive).containsExactly(false);
        assertThat(processedSeq()).isEqualTo(1);
    }

    @Test
    void callerHoldingTheConversationLockIsNotForcedIntoASecondConnection() {
        userMessage("m1", 1, "以后都用表格。");
        RecordingTransactionManager recorder = new RecordingTransactionManager(jdbc.getDataSource());
        ConversationTopicProjectionV2Service recording = projectionService(recorder);

        // 模拟研究路径：外层 turn 事务先 update conversation 分配 seq，再在事务里刷新切分。
        new TransactionTemplate(recorder).executeWithoutResult(status -> {
            jdbc.update("update conversation set workspace_id = workspace_id where id = ?", CONVERSATION);
            recording.refresh(WORKSPACE, CONVERSATION);
        });

        // 外层事务已握着会话行锁：这里不再开第二条连接（否则要到锁超时才失败），
        // 代价是模型调用仍在外层事务内——研究路径的已知残留，本批不改研究路径。
        assertThat(recorder.propagations).containsExactly(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(extractor.transactionActive).containsExactly(true);
        assertThat(processedSeq()).isEqualTo(1);
        assertThat(constraintIds()).hasSize(1);
    }

    @Test
    void concurrentWinnerWithAnEarlierCutoffIsReappliedWithoutCallingTheModelAgain() {
        userMessage("m1", 1, "以后都用表格。");
        userMessage("m3", 3, "这次用中文。");
        extractor.responder = request -> "m3".equals(request.messageId())
                ? languageConstraint("中文") : tableConstraint();
        // 抽取期间另一个 refresh（cutoff 只到 m1）先提交：水位推进到 1，它的行也落库了。
        extractor.onCall = request -> {
            if ("m1".equals(request.messageId())) {
                winnerApplied(1, new ConstraintOpMerger.Step("m1", 1, "",
                        List.of(new ConstraintOp(ConstraintOp.Op.ADD, ConstraintSlot.TABLE, "format.table",
                                ConstraintScope.CONVERSATION, null, 0.9))));
            }
        };

        topics.refresh(WORKSPACE, CONVERSATION);

        // 冲突后不重抽：模型每条消息只调一次，重试用的是已经拿到的 ops。
        assertThat(extractor.messageIds).containsExactly("m1", "m3");
        // 重新读取水位后把 m3 折进胜者的基线：这次回答要用的两条约束都在库里、都是 ACTIVE。
        assertThat(processedSeq()).isEqualTo(3);
        assertThat(constraintText(ConstraintIds.of("m1", "table", 0))).isEqualTo("format.table");
        assertThat(constraintText(ConstraintIds.of("m3", "language", 0))).isEqualTo("中文");
        assertThat(constraintStatus(ConstraintIds.of("m1", "table", 0))).isEqualTo("ACTIVE");
        assertThat(constraintStatus(ConstraintIds.of("m3", "language", 0))).isEqualTo("ACTIVE");
        assertThat(stepsJson()).contains("m1").contains("m3");
    }

    @Test
    void concurrentWinnerThatAlreadyCoveredEveryMessageIsReusedWithoutRewriting() {
        userMessage("m1", 1, "以后用英文。");
        // 胜者的 cutoff 与本轮相同：它写下的行就是本轮要编译到的行。
        extractor.onCall = request -> winnerApplied(1, new ConstraintOpMerger.Step("m1", 1, "",
                List.of(new ConstraintOp(ConstraintOp.Op.ADD, ConstraintSlot.LANGUAGE, "英文",
                        ConstraintScope.CONVERSATION, null, 0.9))));

        topics.refresh(WORKSPACE, CONVERSATION);

        // 本轮没有需要补写的消息：不再推进水位、也不覆盖胜者的行。
        assertThat(extractor.messageIds).containsExactly("m1");
        assertThat(processedSeq()).isEqualTo(1);
        assertThat(lockVersion()).isEqualTo(1);
        assertThat(constraintText(ConstraintIds.of("m1", "language", 0))).isEqualTo("英文");
    }

    @Test
    void deletedProgressRowIsStillAbandonedWithoutWriting() {
        userMessage("m1", 1, "以后用英文。");
        // 消息删除清理了进度行（它自己已经把约束标 REVOKED）：本轮不再把行写回。
        extractor.onCall = request -> jdbc.update("""
                delete from conversation_constraint_state_v2 where conversation_id = ?
                """, CONVERSATION);

        topics.refresh(WORKSPACE, CONVERSATION);

        assertThat(constraintIds()).isEmpty();
        assertThat(jdbc.queryForList("""
                select conversation_id from conversation_constraint_state_v2
                """, String.class)).isEmpty();
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

    @Test
    void failedVersionRebuildDoesNotPublishNewVersionOrPartialRows() {
        userMessage("m1", 1, "以后用表格。");
        userMessage("m3", 3, "以后用中文。");
        topics.refresh(WORKSPACE, CONVERSATION);
        String before = stepsJson();
        int version = lockVersion();
        jdbc.update("update conversation_constraint_state_v2 set rule_version = 'old' where conversation_id = ?",
                CONVERSATION);
        extractor.responder = request -> "m1".equals(request.messageId())
                ? languageConstraint("英文") : new ConstraintOpsExtractor.Result.Unusable("TIMEOUT");
        topics.refresh(WORKSPACE, CONVERSATION);
        assertThat(processedSeq()).isEqualTo(3);
        assertThat(lockVersion()).isEqualTo(version);
        assertThat(stepsJson()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select rule_version from conversation_constraint_state_v2", String.class))
                .isEqualTo("old");
        assertThat(constraintText(ConstraintIds.of("m1", "table", 0))).isEqualTo("format.table");
    }

    @Test
    void concurrentDifferentPrefixRepreparesDependentNoopOutsideTheTransaction() throws Exception {
        userMessage("m1", 1, "以后用英文。");
        userMessage("m3", 3, "继续用中文。");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        extractor.onCall = request -> {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                await(release);
            }
        };
        extractor.responder = request -> {
            if ("m1".equals(request.messageId())) return languageConstraint("中文");
            boolean chinese = request.active().stream().anyMatch(active -> "中文".equals(active.value()));
            return chinese ? new ConstraintOpsExtractor.Result.Ok(List.of(new ConstraintOp(
                    ConstraintOp.Op.NOOP, null, null, null, null, 0.9)), ConstraintOpsPrompt.PROMPT_VERSION)
                    : languageConstraint("中文");
        };
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var running = pool.submit(() -> topics.refresh(WORKSPACE, CONVERSATION));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // 第二连接取得同一会话锁，证明等待模型的线程没有持有该锁。
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status -> {
                jdbc.queryForObject("select id from conversation where id = ? for update", String.class, CONVERSATION);
                winnerApplied(1, new ConstraintOpMerger.Step("m1", 1, "", List.of(new ConstraintOp(
                        ConstraintOp.Op.ADD, ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION, null, 0.9))));
            });
            release.countDown();
            running.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(extractor.messageIds).containsExactly("m1", "m3", "m3");
            assertThat(extractor.transactionActive).containsOnly(false);
            assertThat(constraintText(ConstraintIds.of("m3", "language", 0))).isEqualTo("中文");
            assertThat(constraintStatus(ConstraintIds.of("m1", "language", 0))).isEqualTo("REVOKED");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("等待并发测试超时");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    @Test
    void forbiddenPolaritySurvivesTheStepsJsonBaselineAcrossRefreshes() {
        userMessage("m1", 1, "不要表格。");
        extractor.responder = request -> forbiddenTableConstraint();

        topics.refresh(WORKSPACE, CONVERSATION);

        String forbidId = ConstraintIds.of("m1", "table", 0);
        // 禁止项落成可读文本，极性同时写进 steps_json 的基线 ops 与 polarity 列。
        assertThat(constraintText(forbidId)).isEqualTo("不要使用表格");
        assertThat(constraintPolarity(forbidId)).isEqualTo("FORBID");
        assertThat(stepsJson()).contains("\"polarity\":\"FORBID\"");

        userMessage("m3", 3, "改用表格。");
        extractor.responder = request -> "m3".equals(request.messageId())
                ? tableConstraint() : forbiddenTableConstraint();

        topics.refresh(WORKSPACE, CONVERSATION);

        // 第二轮只抽 m3，但基线里的 m1 仍是 FORBID：正向 table 必须把禁止项顶掉。
        assertThat(extractor.messageIds).containsExactly("m1", "m3");
        assertThat(constraintStatus(forbidId)).isEqualTo("REVOKED");
        String requireId = ConstraintIds.of("m3", "table", 0);
        assertThat(constraintStatus(requireId)).isEqualTo("ACTIVE");
        assertThat(constraintText(requireId)).isEqualTo("format.table");
        assertThat(constraintPolarity(requireId)).isEqualTo("REQUIRE");
        assertThat(constraintPolarity(forbidId)).isEqualTo("FORBID");
    }

    private static ConstraintOpsExtractor.Result forbiddenTableConstraint() {
        return new ConstraintOpsExtractor.Result.Ok(List.of(new ConstraintOp(ConstraintOp.Op.ADD,
                ConstraintSlot.TABLE, "format.table", ConstraintScope.CONVERSATION, null, 0.9,
                ConstraintPolarity.FORBID)), ConstraintOpsPrompt.PROMPT_VERSION);
    }

    private static ConstraintOpsExtractor.Result tableConstraint() {
        return new ConstraintOpsExtractor.Result.Ok(List.of(new ConstraintOp(ConstraintOp.Op.ADD,
                ConstraintSlot.TABLE, "format.table", ConstraintScope.CONVERSATION, null, 0.9)),
                ConstraintOpsPrompt.PROMPT_VERSION);
    }

    private static ConstraintOpsExtractor.Result languageConstraint(String value) {
        return new ConstraintOpsExtractor.Result.Ok(List.of(new ConstraintOp(ConstraintOp.Op.ADD,
                ConstraintSlot.LANGUAGE, value, ConstraintScope.CONVERSATION, null, 0.9)),
                ConstraintOpsPrompt.PROMPT_VERSION);
    }

    /** 模拟「另一个 refresh 先提交」：水位与基线写进库，约束行按合并结果落 ACTIVE。 */
    private void winnerApplied(int processedSeq, ConstraintOpMerger.Step step) {
        jdbc.update("""
                update conversation_constraint_state_v2
                set processed_seq = ?, steps_json = ?, lock_version = lock_version + 1
                where conversation_id = ?
                """, processedSeq, stepsJson(List.of(step)), CONVERSATION);
        for (ConversationConstraintProjectorV2.Constraint row
                : new ConstraintOpMerger(0.55).fold(List.of(step))) {
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

    private static String stepsJson(List<ConstraintOpMerger.Step> steps) {
        try {
            return new ObjectMapper().writeValueAsString(steps);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
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

    private String constraintStatus(String id) {
        return jdbc.queryForObject("select status from conversation_constraint_v2 where id = ?",
                String.class, id);
    }

    private String constraintText(String id) {
        return jdbc.queryForObject("select constraint_text from conversation_constraint_v2 where id = ?",
                String.class, id);
    }

    private String constraintPolarity(String id) {
        return jdbc.queryForObject("select polarity from conversation_constraint_v2 where id = ?",
                String.class, id);
    }

    private String stepsJson() {
        return jdbc.queryForObject("""
                select steps_json from conversation_constraint_state_v2 where conversation_id = ?
                """, String.class, CONVERSATION);
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
    }

    /** 记录每次开事务时用的传播行为，用来验证 REQUIRES_NEW 与「调用方带事务」两种情形。 */
    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private final PlatformTransactionManager delegate;
        final List<Integer> propagations = new ArrayList<>();

        RecordingTransactionManager(javax.sql.DataSource dataSource) {
            this.delegate = new DataSourceTransactionManager(dataSource);
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            propagations.add(definition == null
                    ? TransactionDefinition.PROPAGATION_REQUIRED : definition.getPropagationBehavior());
            return delegate.getTransaction(definition);
        }

        @Override
        public void commit(TransactionStatus status) {
            delegate.commit(status);
        }

        @Override
        public void rollback(TransactionStatus status) {
            delegate.rollback(status);
        }
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
