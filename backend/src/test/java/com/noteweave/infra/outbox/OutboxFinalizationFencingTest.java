package com.noteweave.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.infra.outbox.OutboxFinalizationRepository.ClaimedFinalization;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionFinalizer;
import com.noteweave.source.SourceParseFailureFinalizer;
import com.noteweave.source.SourceTopicNames;
import com.noteweave.task.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * FB-01 验收：人工重驱与死信补偿的代次隔离。
 *
 * <ul>
 *   <li>DEAD_LETTER → 重驱成功 → 旧代次补偿到达：围栏失败，无业务副作用（RB-001 核心）；</li>
 *   <li>补偿先执行、重驱后到：围栏通过，业务收尾提交（正常路径）；</li>
 *   <li>同 owner 两次认领：第一次的迟到 finish/retry 被一次性 claim_token 拒绝（RB-003）；</li>
 *   <li>未知 business_kind 与不可恢复身份进入 TERMINAL_FAILURE，不静默消失（RB-002）；</li>
 *   <li>KAKKA_DLT store 以「登记后无 TASK_REDRIVEN 事件」为业务阶段围栏。</li>
 * </ul>
 *
 * <p>时序用可控桩（先改 outbox 行状态再执行补偿）表达，不依赖 sleep；
 * MySQL 行锁下的真实并发交错为未执行项，见 validation 说明。
 */
class OutboxFinalizationFencingTest {

    private JdbcTemplate jdbcTemplate;
    private OutboxFinalizationRepository repository;
    private OutboxFinalizationExecutor executor;
    private TaskService taskService;
    private int sequence;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:finalization-fence-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table outbox_finalization (
                    id varchar(36) primary key, workspace_id varchar(36), store_type varchar(32) not null,
                    outbox_id varchar(190) not null, delivery_generation int not null default 1,
                    business_kind varchar(64) not null, business_identity_json varchar(1000) not null,
                    status varchar(32) not null, attempt_count int not null default 0,
                    max_attempts int not null default 5, next_attempt_at timestamp,
                    lease_owner varchar(128), lease_until timestamp, claim_token varchar(64),
                    last_error_code varchar(128), last_error_summary varchar(500),
                    created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp,
                    completed_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table task_outbox (
                    id varchar(36) primary key, task_id varchar(36), topic varchar(160) not null,
                    message_key varchar(160) not null, payload_json clob not null, status varchar(32) not null,
                    attempt_count int not null default 0, last_error varchar(1000), claimed_at timestamp,
                    next_attempt_at timestamp, lease_owner varchar(128), lease_until timestamp,
                    dead_lettered_at timestamp, sent_at timestamp, created_at timestamp default current_timestamp,
                    semantic_key varchar(64), delivery_generation int not null default 1, payload_digest varchar(64)
                )
                """);
        jdbcTemplate.execute("""
                create table task_event (
                    id varchar(36) primary key, task_id varchar(36) not null, event_type varchar(64) not null,
                    message varchar(1000), payload_json clob, created_at timestamp default current_timestamp
                )
                """);
        PlatformTransactionManager transactionManager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        repository = new OutboxFinalizationRepository(
                jdbcTemplate, new ObjectMapper(), transactionManager);
        taskService = mock(TaskService.class);
        NoteWeaveProperties properties = new NoteWeaveProperties(null, null, null,
                new NoteWeaveProperties.Kafka(true, new NoteWeaveProperties.Topics(
                        "noteweave.source.parse", "noteweave.retrieval.projection",
                        "noteweave.wiki.ingest", "noteweave.wiki.retract",
                        "noteweave.conversation.summary")),
                null, null);
        executor = new OutboxFinalizationExecutor(
                repository,
                mock(SourceParseFailureFinalizer.class),
                mock(SourceRetrievalProjectionFinalizer.class),
                taskService,
                new SourceTopicNames(properties),
                new ObjectMapper(),
                emptyFinalizerProvider(),
                new SimpleMeterRegistry(),
                jdbcTemplate,
                transactionManager,
                20, 120, 300);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<OutboxBusinessFinalizer> emptyFinalizerProvider() {
        ObjectProvider<OutboxBusinessFinalizer> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(java.util.stream.Stream.of());
        return provider;
    }

    private String deadLetteredOutbox(String taskId) {
        String outboxId = "outbox-" + ++sequence;
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status,
                    attempt_count, delivery_generation, dead_lettered_at)
                values (?, ?, 'noteweave.source.parse', ?, '{}', 'DEAD_LETTER', 5, 1, current_timestamp)
                """, outboxId, taskId, taskId);
        return outboxId;
    }

    /** 把新登记的补偿推迟，避免与同上下文其他扫描竞争；测试前再放到期。 */
    private String registerDeferred(String storeType, String outboxId, int generation, String kind,
            Map<String, Object> identity) {
        repository.insertPending(storeType, outboxId, generation, kind, "ws-1", identity, 5);
        String id = jdbcTemplate.queryForObject(
                "select id from outbox_finalization where store_type = ? and outbox_id = ? and delivery_generation = ?",
                String.class, storeType, outboxId, generation);
        jdbcTemplate.update("""
                update outbox_finalization set next_attempt_at = timestamp '2030-01-01 00:00:00'
                where id = ?
                """, id);
        return id;
    }

    private ClaimedFinalization claimSingle(String intentId) {
        jdbcTemplate.update(
                "update outbox_finalization set next_attempt_at = null where id = ?", intentId);
        List<ClaimedFinalization> claimed = repository.claimDue("fence-test", 50, Duration.ofSeconds(120));
        return claimed.stream().filter(work -> work.id().equals(intentId)).findFirst().orElseThrow();
    }

    private String intentStatus(String intentId) {
        return jdbcTemplate.queryForObject(
                "select status from outbox_finalization where id = ?", String.class, intentId);
    }

    @Test
    void compensationBeforeRedriveAppliesAndFailsTask() {
        String taskId = "task-ok-" + ++sequence;
        String outboxId = deadLetteredOutbox(taskId);
        String intentId = registerDeferred(OutboxFinalizationRepository.STORE_TASK, outboxId, 1,
                OutboxFinalizationExecutor.KIND_TASK_FALLBACK, Map.of("taskId", taskId));

        executor.execute(claimSingle(intentId));

        assertThat(intentStatus(intentId)).isEqualTo(OutboxFinalizationRepository.STATUS_APPLIED);
        verify(taskService).failTask(taskId, "OUTBOX_DEAD_LETTER", "Kafka Outbox 投递耗尽，已进入死信",
                "OUTBOX_DISPATCH_EXHAUSTED", true);
    }

    @Test
    void lateCompensationAfterRedriveMustBeIgnoredWithoutSideEffects() {
        String taskId = "task-redriven-" + ++sequence;
        String outboxId = deadLetteredOutbox(taskId);
        String intentId = registerDeferred(OutboxFinalizationRepository.STORE_TASK, outboxId, 1,
                OutboxFinalizationExecutor.KIND_TASK_FALLBACK, Map.of("taskId", taskId));
        // 人工重驱成功：outbox 行换新代次回到 READY（与 TaskOutboxDispatcherService.redrive 同一 CAS 语义）
        jdbcTemplate.update("""
                update task_outbox set status = 'READY', delivery_generation = 2,
                    dead_lettered_at = null, next_attempt_at = current_timestamp
                where id = ? and status = 'DEAD_LETTER' and delivery_generation = 1
                """, outboxId);
        repository.cancelSuperseded(OutboxFinalizationRepository.STORE_TASK, outboxId, 1,
                "OUTBOX_REDRIVE_SUPERSEDED");
        // 重驱后补偿意图已同事务失效：认领不到（这里直接验证失效状态）
        assertThat(intentStatus(intentId)).isEqualTo(OutboxFinalizationRepository.STATUS_IGNORED_STALE);
        verify(taskService, org.mockito.Mockito.never()).failTask(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void runningCompensationLosingGenerationRaceMustNotFailRedrivenBusiness() {
        String taskId = "task-race-" + ++sequence;
        String outboxId = deadLetteredOutbox(taskId);
        String intentId = registerDeferred(OutboxFinalizationRepository.STORE_TASK, outboxId, 1,
                OutboxFinalizationExecutor.KIND_TASK_FALLBACK, Map.of("taskId", taskId));
        // 补偿已认领（RUNNING）后、执行业务前，人工重驱完成换代
        ClaimedFinalization work = claimSingle(intentId);
        jdbcTemplate.update("""
                update task_outbox set status = 'READY', delivery_generation = 2,
                    dead_lettered_at = null, next_attempt_at = current_timestamp
                where id = ?
                """, outboxId);

        executor.execute(work);

        // 围栏在业务事务内失败：无副作用退出，任务没有被旧补偿打回失败
        assertThat(intentStatus(intentId)).isEqualTo(OutboxFinalizationRepository.STATUS_IGNORED_STALE);
        verify(taskService, org.mockito.Mockito.never()).failTask(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void kafkaDltCompensationFencedByTaskRedrivenEventAfterRegistration() {
        String taskId = "task-dlt-" + ++sequence;
        String intentId = registerDeferred(OutboxFinalizationRepository.STORE_KAFKA_DLT,
                "noteweave.source.parse:0:42:noteweave", 1,
                OutboxFinalizationExecutor.KIND_TASK_FALLBACK, Map.of("taskId", taskId));
        // DLT 登记之后发生过人工重驱：当前业务阶段已不是死信登记时的事实
        jdbcTemplate.update("""
                insert into task_event(id, task_id, event_type, message)
                values (?, ?, 'TASK_REDRIVEN', 'Outbox 死信已人工重驱')
                """, "evt-" + ++sequence, taskId);

        executor.execute(claimSingle(intentId));

        assertThat(intentStatus(intentId)).isEqualTo(OutboxFinalizationRepository.STATUS_IGNORED_STALE);
        verify(taskService, org.mockito.Mockito.never()).failTask(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void kafkaDltCompensationWithoutRedriveEventStillApplies() {
        String taskId = "task-dlt-ok-" + ++sequence;
        String intentId = registerDeferred(OutboxFinalizationRepository.STORE_KAFKA_DLT,
                "noteweave.source.parse:0:43:noteweave", 1,
                OutboxFinalizationExecutor.KIND_TASK_FALLBACK, Map.of("taskId", taskId));

        executor.execute(claimSingle(intentId));

        assertThat(intentStatus(intentId)).isEqualTo(OutboxFinalizationRepository.STATUS_APPLIED);
    }

    @Test
    void unknownBusinessKindMustEndTerminalInsteadOfSilentStale() {
        String intentId = registerDeferred(OutboxFinalizationRepository.STORE_TASK, "outbox-unknown", 1,
                "TOTALLY_UNKNOWN_KIND", Map.of("taskId", "task-x"));

        executor.execute(claimSingle(intentId));

        assertThat(intentStatus(intentId)).isEqualTo(OutboxFinalizationRepository.STATUS_TERMINAL_FAILURE);
        verify(taskService, org.mockito.Mockito.never()).failTask(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void unrecoverableIdentityMustEndTerminalInsteadOfEmptyIdentityStale() {
        String intentId = registerDeferred(OutboxFinalizationRepository.STORE_TASK, "outbox-broken", 1,
                OutboxFinalizationExecutor.KIND_TASK_FALLBACK, Map.of("taskId", "task-y"));
        // 历史残缺行：身份 JSON 被截断不可解析（绕过 safeIdentityJson 模拟存量脏数据）
        jdbcTemplate.update(
                "update outbox_finalization set business_identity_json = '{\"taskId\":\"trun' where id = ?",
                intentId);

        executor.execute(claimSingle(intentId));

        assertThat(intentStatus(intentId)).isEqualTo(OutboxFinalizationRepository.STATUS_TERMINAL_FAILURE);
    }

    @Test
    void staleClaimTokenWriteBackMustBeRejectedEvenForSameOwner() {
        String taskId = "task-token-" + ++sequence;
        String outboxId = deadLetteredOutbox(taskId);
        String intentId = registerDeferred(OutboxFinalizationRepository.STORE_TASK, outboxId, 1,
                OutboxFinalizationExecutor.KIND_TASK_FALLBACK, Map.of("taskId", taskId));
        ClaimedFinalization first = claimSingle(intentId);
        // 同 owner 租约过期后重新认领：新 claim_token 生成
        jdbcTemplate.update(
                "update outbox_finalization set lease_until = timestamp '2020-01-01 00:00:00' where id = ?",
                intentId);
        ClaimedFinalization second = claimSingle(intentId);
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());

        // 第一次认领的迟到写回被拒绝（finish 与 retryLater 都是 id+claimToken CAS）
        assertThat(repository.finish(first.id(), first.claimToken(),
                OutboxFinalizationRepository.STATUS_APPLIED, java.time.Instant.now())).isFalse();
        assertThat(repository.retryLater(first.id(), first.claimToken(), "DB_BUSY", "late", 60)).isFalse();
        assertThat(intentStatus(intentId)).isEqualTo(OutboxFinalizationRepository.STATUS_RUNNING);
    }

    @Test
    void oversizeIdentityIsReducedToEssentialKeysAndNeverTruncatedMidJson() {
        Map<String, Object> identity = new java.util.HashMap<>();
        identity.put("taskId", "task-z");
        identity.put("workspaceId", "ws-1");
        identity.put("junk", "x".repeat(4000));
        String json = repository.safeIdentityJson(identity);

        assertThat(json.length()).isLessThanOrEqualTo(1000);
        assertThat(json).contains("\"taskId\"").doesNotContain("junk");
    }

    @Test
    void cancelSupersededInvalidatesOnlyTheOldGeneration() {
        String taskId = "task-cancel-" + ++sequence;
        String outboxId = deadLetteredOutbox(taskId);
        repository.insertPending(OutboxFinalizationRepository.STORE_TASK, outboxId, 1,
                OutboxFinalizationExecutor.KIND_TASK_FALLBACK, "ws-1", Map.of("taskId", taskId), 5);
        repository.insertPending(OutboxFinalizationRepository.STORE_TASK, outboxId, 2,
                OutboxFinalizationExecutor.KIND_TASK_FALLBACK, "ws-1", Map.of("taskId", taskId), 5);

        int cancelled = repository.cancelSuperseded(OutboxFinalizationRepository.STORE_TASK, outboxId, 1,
                "OUTBOX_REDRIVE_SUPERSEDED");

        assertThat(cancelled).isEqualTo(1);
        assertThat(repository.statusOf(OutboxFinalizationRepository.STORE_TASK, outboxId, 1))
                .isEqualTo(OutboxFinalizationRepository.STATUS_IGNORED_STALE);
        assertThat(repository.statusOf(OutboxFinalizationRepository.STORE_TASK, outboxId, 2))
                .isEqualTo(OutboxFinalizationRepository.STATUS_PENDING);
    }

    @Test
    void cancelSupersededByTaskMatchesKafkaDltIdentity() {
        String taskId = "task-bytask-" + ++sequence;
        repository.insertPending(OutboxFinalizationRepository.STORE_KAFKA_DLT,
                "noteweave.source.parse:0:99:noteweave", 1,
                OutboxFinalizationExecutor.KIND_KAFKA_DLT_PARSE, "ws-1",
                Map.of("taskId", taskId, "workspaceId", "ws-1", "sourceId", "s-1", "snapshotId", "ss-1"), 5);

        int cancelled = repository.cancelSupersededByTask(
                OutboxFinalizationRepository.STORE_KAFKA_DLT, taskId, "OUTBOX_REDRIVE_SUPERSEDED");

        assertThat(cancelled).isEqualTo(1);
        assertThat(repository.statusOf(OutboxFinalizationRepository.STORE_KAFKA_DLT,
                "noteweave.source.parse:0:99:noteweave", 1))
                .isEqualTo(OutboxFinalizationRepository.STATUS_IGNORED_STALE);
    }
}
