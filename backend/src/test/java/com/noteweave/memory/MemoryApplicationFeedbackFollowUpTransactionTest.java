package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.memory.MemoryApplicationFeedbackService.FollowUpResult;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.task.TaskService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 跟进反馈的判定模型在事务外调用、写入放进短事务并做乐观检查。
 * <p>
 * 用内存 H2 起最小的几张表直接构造服务，不启动 Spring 上下文，模型用 stub。
 * 钉住三件事：模型调用时没有活动事务且不持锁、判定期间 usage 行或对应反馈被别的处理改过时一行都不写、
 * 正常路径按原语义写入接受/负面；另外确认判断失败时只重试一次、返回 {@code RETRYABLE} 且不开事务。
 */
class MemoryApplicationFeedbackFollowUpTransactionTest {

    private static final String WORKSPACE = "ws-1";
    private static final String CONVERSATION = "conv-1";
    private static final String ASSISTANT_MESSAGE = "msg-a";
    private static final String USER_MESSAGE = "msg-u";
    private static final String LENGTH_ITEM = "item-1";
    private static final String TERM_ITEM = "item-2";
    private static final String POSITIVE = "{\"feedback\":true,\"polarity\":\"POSITIVE\",\"aspect\":\"UNKNOWN\"}";
    private static final String NEGATIVE_LENGTH =
            "{\"feedback\":true,\"polarity\":\"NEGATIVE\",\"aspect\":\"LENGTH\"}";

    private JdbcTemplate jdbc;
    private StubClient client;

    @BeforeEach
    void setUp() {
        client = new StubClient();
        client.output = POSITIVE;
        DataSource dataSource = dataSource();
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        jdbc.update("insert into conversation_message(id, conversation_id, workspace_id, message_seq, role, content)"
                + " values (?, ?, ?, 1, 'ASSISTANT', '回答正文')", ASSISTANT_MESSAGE, CONVERSATION, WORKSPACE);
        jdbc.update("insert into conversation_message(id, conversation_id, workspace_id, message_seq, role, content)"
                + " values (?, ?, ?, 2, 'USER', '谢谢，这样讲很清楚')", USER_MESSAGE, CONVERSATION, WORKSPACE);
        insertItem(LENGTH_ITEM, "answer.length");
        insertItem(TERM_ITEM, "terminology.term");
        insertUsage("usage-1", LENGTH_ITEM);
        insertUsage("usage-2", TERM_ITEM);
    }

    @Test
    void modelJudgmentRunsOutsideTheTransactionAndTheAcceptanceIsStillWritten() {
        RecordingTransactionManager manager = new RecordingTransactionManager(jdbc.getDataSource());
        MemoryApplicationFeedbackService feedback = feedback(manager);

        FollowUpResult result = feedback.recordFollowUp(WORKSPACE, USER_MESSAGE, "谢谢，这样讲很清楚");

        assertThat(result).isEqualTo(FollowUpResult.ACCEPTED);
        // 判定模型被调用时没有任何活动事务：不会在 memory_usage_log / memory_item 的行锁里等模型
        assertThat(client.transactionActive).containsExactly(false);
        // 只有写库那一段开事务，且只开一段
        assertThat(manager.propagations).containsExactly(TransactionDefinition.PROPAGATION_REQUIRED);
        // 正常路径按原语义写入：两条 usage 各记一次半权的接受
        assertThat(followUpItems()).containsExactlyInAnyOrder(LENGTH_ITEM, TERM_ITEM);
        assertThat(outcomeOf("usage-1")).isEqualTo("ACCEPTED");
        assertThat(outcomeOf("usage-2")).isEqualTo("ACCEPTED");
        assertThat(weightOf(LENGTH_ITEM)).isCloseTo(0.5, within(0.0001));
        assertThat(utility(LENGTH_ITEM)).isCloseTo(0.7813, within(0.0001));
        assertThat(count(LENGTH_ITEM, "positive_outcome_count")).isEqualTo(1);
        assertThat(count(LENGTH_ITEM, "application_count")).isEqualTo(1);
    }

    @Test
    void negativeJudgmentOnlyTouchesTheMatchingSlotAndStillRunsTheModelOutsideATransaction() {
        client.output = NEGATIVE_LENGTH;
        RecordingTransactionManager manager = new RecordingTransactionManager(jdbc.getDataSource());
        MemoryApplicationFeedbackService feedback = feedback(manager);

        FollowUpResult result = feedback.recordFollowUp(WORKSPACE, USER_MESSAGE, "太长了");

        assertThat(result).isEqualTo(FollowUpResult.NEGATIVE);
        assertThat(client.transactionActive).containsExactly(false);
        assertThat(manager.propagations).containsExactly(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(followUpItems()).containsExactly(LENGTH_ITEM);
        assertThat(count(LENGTH_ITEM, "negative_outcome_count")).isEqualTo(1);
        assertThat(count(TERM_ITEM, "negative_outcome_count")).isZero();
        assertThat(text(LENGTH_ITEM, "review_status")).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void modelFailureIsRetryableOnceWithoutOpeningATransactionOrWriting() {
        client.output = "不是 JSON";
        RecordingTransactionManager manager = new RecordingTransactionManager(jdbc.getDataSource());
        MemoryApplicationFeedbackService feedback = feedback(manager);

        FollowUpResult result = feedback.recordFollowUp(WORKSPACE, USER_MESSAGE, "谢谢，这样讲很清楚");

        // 保留「只重试一次 + RETRYABLE」的语义：判定失败不动锁、不写库，由调用方标可重试
        assertThat(result).isEqualTo(FollowUpResult.RETRYABLE);
        assertThat(client.calls).isEqualTo(2);
        assertThat(client.transactionActive).containsExactly(false, false);
        assertThat(manager.propagations).isEmpty();
        assertThat(followUpItems()).isEmpty();
        assertThat(utility(LENGTH_ITEM)).isCloseTo(0.75, within(0.0001));
    }

    /**
     * 判定期间同一 usage 行被别的处理（例如同一回答的评分）写了反馈：乐观检查失败，本次一行都不写。
     */
    @Test
    void usageRowChangedDuringTheJudgmentDropsTheWrite() {
        client.onCall = () -> jdbc.update("""
                update memory_usage_log set outcome_type = 'ACCEPTED', outcome_score = 1
                where id = 'usage-1'
                """);
        MemoryApplicationFeedbackService feedback = feedback(jdbc.getDataSource());

        FollowUpResult result = feedback.recordFollowUp(WORKSPACE, USER_MESSAGE, "谢谢，这样讲很清楚");

        assertThat(result).isEqualTo(FollowUpResult.SKIPPED);
        assertThat(followUpItems()).isEmpty();
        assertThat(utility(LENGTH_ITEM)).isCloseTo(0.75, within(0.0001));
        assertThat(count(LENGTH_ITEM, "positive_outcome_count")).isZero();
        // 另一条行也不能被顺带写掉
        assertThat(outcomeOf("usage-2")).isNull();
    }

    /** 判定期间这条回答又多了一条注入记录：快照和加锁重读的行集对不上，同样放弃写入。 */
    @Test
    void usageRowAddedDuringTheJudgmentDropsTheWrite() {
        client.onCall = () -> insertUsage("usage-3", TERM_ITEM);
        MemoryApplicationFeedbackService feedback = feedback(jdbc.getDataSource());

        FollowUpResult result = feedback.recordFollowUp(WORKSPACE, USER_MESSAGE, "谢谢，这样讲很清楚");

        assertThat(result).isEqualTo(FollowUpResult.SKIPPED);
        assertThat(followUpItems()).isEmpty();
        assertThat(outcomeOf("usage-1")).isNull();
    }

    /**
     * 记忆抽取把跟进反馈放在自己的持久化事务外先跑完：
     * 否则判定模型又会落回 {@code memory_usage_log ... for update} 之后。
     */
    @Test
    void extractionJudgesTheFollowUpOutsideThePersistTransaction() {
        TaskService taskService = mock(TaskService.class);
        jdbc.update("insert into task(id, task_status) values ('task-1', 'RUNNING')");
        MemoryApplicationFeedbackService feedback = feedback(jdbc.getDataSource());
        MemoryConversationExtractionService extraction = extractionService(taskService, feedback);

        extraction.persistExtraction("task-1", WORKSPACE, USER_MESSAGE, "user-1",
                new MemoryConversationExtractionService.Extraction(
                        List.of(), MemoryConversationExtractionService.METHOD_LLM), "谢谢，这样讲很清楚");

        assertThat(client.transactionActive).containsExactly(false);
        assertThat(followUpItems()).containsExactlyInAnyOrder(LENGTH_ITEM, TERM_ITEM);
        verify(taskService).completeTask(anyString(), anyString(), anyString(), anyString());
    }

    private MemoryApplicationFeedbackService feedback(DataSource dataSource) {
        return feedback(new DataSourceTransactionManager(dataSource));
    }

    private MemoryApplicationFeedbackService feedback(PlatformTransactionManager transactionManager) {
        return new MemoryApplicationFeedbackService(jdbc, mock(WorkspaceAccessGuard.class),
                new MemoryOutcomePolicy(), new MemoryFeedbackAspectJudge(client, new ObjectMapper()),
                transactionManager, true);
    }

    private MemoryConversationExtractionService extractionService(
            TaskService taskService, MemoryApplicationFeedbackService feedback) {
        MemoryConversationExtractionService service = new MemoryConversationExtractionService(jdbc,
                new ObjectMapper(), taskService, mock(MemoryCandidatePolicy.class),
                mock(MemoryCandidateService.class), mock(CanonicalMemoryReviewService.class),
                mock(MemoryStatementMatcher.class), mock(ChatLlmClient.class),
                mock(MemoryEvidenceService.class), mock(MemoryWordingJudge.class),
                new DataSourceTransactionManager(jdbc.getDataSource()),
                "noteweave.memory.extraction", true);
        service.setApplicationFeedback(feedback);
        return service;
    }

    private void insertItem(String id, String slot) {
        jdbc.update("""
                insert into memory_item(id, workspace_id, current_revision_id, semantic_slot_key,
                    utility_score, status, review_status, evidence_beta)
                values (?, ?, ?, ?, 0.75, 'ACTIVE', 'APPROVED', 1)
                """, id, WORKSPACE, "rev-" + id, slot);
    }

    private void insertUsage(String id, String itemId) {
        jdbc.update("""
                insert into memory_usage_log(id, memory_item_id, memory_revision_id, workspace_id,
                    task_type, target_type, target_id, compiled_as, outcome_policy_version)
                values (?, ?, ?, ?, 'QA', 'CONVERSATION_MESSAGE', ?, 'CHAT', ?)
                """, id, itemId, "rev-" + itemId, WORKSPACE, ASSISTANT_MESSAGE, MemoryOutcomePolicy.VERSION);
    }

    private List<String> followUpItems() {
        return jdbc.queryForList("""
                select memory_item_id from memory_application_outcome
                where workspace_id = ? and signal_kind = 'FOLLOW_UP' and superseded_at is null
                """, String.class, WORKSPACE);
    }

    private String outcomeOf(String usageId) {
        return jdbc.queryForObject("select outcome_type from memory_usage_log where id = ?", String.class, usageId);
    }

    private double weightOf(String memoryItemId) {
        return jdbc.queryForObject("""
                select weight from memory_application_outcome
                where memory_item_id = ? and signal_kind = 'FOLLOW_UP' and superseded_at is null
                """, Double.class, memoryItemId);
    }

    private double utility(String memoryItemId) {
        return jdbc.queryForObject("select utility_score from memory_item where id = ?", Double.class, memoryItemId);
    }

    private int count(String memoryItemId, String column) {
        return jdbc.queryForObject("select " + column + " from memory_item where id = ?",
                Integer.class, memoryItemId);
    }

    private String text(String memoryItemId, String column) {
        return jdbc.queryForObject("select " + column + " from memory_item where id = ?",
                String.class, memoryItemId);
    }

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:follow-up-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createSchema() {
        jdbc.execute("""
                create table conversation_message (
                    id varchar(36) primary key,
                    conversation_id varchar(36) not null,
                    workspace_id varchar(36) not null,
                    message_seq int not null,
                    role varchar(32) not null,
                    content longtext
                )
                """);
        jdbc.execute("""
                create table memory_item (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    legacy_memory_object_id varchar(36) null,
                    current_revision_id varchar(36) null,
                    semantic_slot_key varchar(128) null,
                    utility_score decimal(8,4) not null default 0.75,
                    application_count int not null default 0,
                    positive_outcome_count int not null default 0,
                    negative_outcome_count int not null default 0,
                    edit_outcome_count int not null default 0,
                    retry_outcome_count int not null default 0,
                    evidence_beta decimal(12,4) not null default 1.5000,
                    status varchar(24) not null default 'ACTIVE',
                    review_status varchar(24) not null default 'APPROVED',
                    outcome_policy_version varchar(64) null,
                    last_outcome_at timestamp null,
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbc.execute("""
                create table memory_usage_log (
                    id varchar(36) primary key,
                    memory_object_id varchar(36) null,
                    memory_version_id varchar(36) null,
                    memory_item_id varchar(36) null,
                    memory_revision_id varchar(36) null,
                    workspace_id varchar(36) not null,
                    task_type varchar(64) null,
                    target_type varchar(64) not null,
                    target_id varchar(128) not null,
                    compiled_as varchar(64) null,
                    outcome_policy_version varchar(64) null,
                    outcome_type varchar(32) null,
                    outcome_score decimal(8,4) null,
                    edit_magnitude decimal(8,4) null,
                    feedback_note varchar(500) null,
                    outcome_at timestamp null,
                    effect_feedback varchar(255) null
                )
                """);
        jdbc.execute("""
                create table memory_application_outcome (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    usage_log_id varchar(36) null,
                    memory_item_id varchar(36) null,
                    memory_revision_id varchar(36) null,
                    target_type varchar(64) not null,
                    target_id varchar(128) not null,
                    signal_kind varchar(32) not null,
                    outcome_type varchar(32) not null,
                    outcome_score decimal(8,4) not null,
                    weight decimal(8,4) not null,
                    beta_weight decimal(8,4) not null default 0,
                    aspect varchar(32) null,
                    feedback_note varchar(500) null,
                    degradation_reason varchar(80) null,
                    source_ref varchar(160) null,
                    edit_magnitude decimal(8,4) null,
                    utility_before decimal(8,4) null,
                    utility_delta decimal(8,4) not null default 0,
                    application_count_before int null,
                    application_delta int not null default 0,
                    positive_count_before int null,
                    positive_delta int not null default 0,
                    negative_count_before int null,
                    negative_delta int not null default 0,
                    edit_count_before int null,
                    edit_delta int not null default 0,
                    retry_count_before int null,
                    retry_delta int not null default 0,
                    status_before varchar(32) null,
                    review_before varchar(32) null,
                    row_no bigint not null,
                    superseded_at timestamp null,
                    created_at timestamp not null default current_timestamp
                )
                """);
        jdbc.execute("""
                create table memory_evidence_session (
                    id varchar(36) primary key,
                    memory_item_id varchar(36) not null,
                    session_key varchar(160) not null,
                    direction varchar(16) not null,
                    weight decimal(8,4) not null,
                    created_at timestamp not null default current_timestamp
                )
                """);
        jdbc.execute("""
                create table memory_signal (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    source_id varchar(160) not null
                )
                """);
        jdbc.execute("""
                create table memory_runtime_revision (
                    id varchar(36) primary key,
                    memory_item_id varchar(36) not null,
                    display_text longtext,
                    normalized_value_json longtext,
                    status varchar(24) not null
                )
                """);
        jdbc.execute("""
                create table task (
                    id varchar(36) primary key,
                    task_status varchar(32) not null
                )
                """);
    }

    /** 记录每次开事务的传播行为，用来验证判定阶段完全不开事务。 */
    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private final PlatformTransactionManager delegate;
        private final List<Integer> propagations = new ArrayList<>();

        private RecordingTransactionManager(DataSource dataSource) {
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

    /** 脚本化反馈判断模型：记下调用次数与调用时是否有活动事务，并按用例替换输出。 */
    private static final class StubClient implements ChatLlmClient {

        private final List<Boolean> transactionActive = new ArrayList<>();
        private String output = POSITIVE;
        private int calls;
        private Runnable onCall = () -> { };

        @Override
        public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                 Consumer<String> onToken) {
            calls++;
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            onCall.run();
            return output;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
