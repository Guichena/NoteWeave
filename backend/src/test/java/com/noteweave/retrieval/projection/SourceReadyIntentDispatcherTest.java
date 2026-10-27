package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.knowledge.WikiIngestIntentOutcome;
import com.noteweave.knowledge.WikiReadyIngestIntentHandler;
import com.noteweave.knowledge.WikiReadySourceIntent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * READY 意图调度循环测试（FB-02 验收，组合验证态：引用 A 的处理器契约类型）：
 * 认领后调用 A 处理器，APPLIED→CONSUMED；RETRYABLE→退避，达上限 FAILED（毒性意图不阻塞）；
 * 处理器 bean 缺失按可重试退避且原因可观察，不静默当成功。
 */
class SourceReadyIntentDispatcherTest {

    private JdbcTemplate jdbcTemplate;
    private SourceReadyIntentRepository intents;
    private WikiReadyIngestIntentHandler handler;
    private SourceReadyIntentDispatcher dispatcher;
    private int sequence;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:ready-dispatch-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table source_ready_intent (
                    id varchar(36) primary key, workspace_id varchar(36) not null,
                    source_id varchar(36) not null, source_snapshot_id varchar(36) not null,
                    ingest_policy_version varchar(64) not null, status varchar(32) not null,
                    lease_owner varchar(128), lease_until timestamp,
                    attempt_count int not null default 0, max_attempts int not null default 5,
                    next_attempt_at timestamp, claim_token varchar(64),
                    last_error_code varchar(128), last_attempt_at timestamp,
                    consumed_at timestamp, created_at timestamp default current_timestamp,
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table source_snapshot (
                    id varchar(36) primary key, source_id varchar(36) not null, version_no int not null
                )
                """);
        intents = new SourceReadyIntentRepository(jdbcTemplate);
        handler = mock(WikiReadyIngestIntentHandler.class);
        dispatcher = new SourceReadyIntentDispatcher(
                intents, provider(handler), jdbcTemplate, new SimpleMeterRegistry(),
                true, 20, 120, 600, 200, "1");
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<WikiReadyIngestIntentHandler> provider(WikiReadyIngestIntentHandler handler) {
        ObjectProvider<WikiReadyIngestIntentHandler> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(handler);
        return provider;
    }

    private String seedIntent() {
        String suffix = String.valueOf(++sequence);
        String snapshot = "snap-" + suffix;
        intents.recordIfAbsent("ws-" + suffix, "src-" + suffix, snapshot, "1");
        jdbcTemplate.update("insert into source_snapshot(id, source_id, version_no) values (?, ?, 3)",
                snapshot, "src-" + suffix);
        return snapshot;
    }

    private String statusOf(String snapshotId) {
        return jdbcTemplate.queryForObject(
                "select status from source_ready_intent where source_snapshot_id = ?", String.class, snapshotId);
    }

    @Test
    void appliedIntentIsConsumedWithAuthoritativeGeneration() {
        String snapshot = seedIntent();
        AtomicReference<WikiReadySourceIntent> seen = new AtomicReference<>();
        when(handler.handle(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            seen.set(invocation.getArgument(0));
            return WikiIngestIntentOutcome.APPLIED;
        });

        dispatcher.dispatchDue();

        assertThat(statusOf(snapshot)).isEqualTo("CONSUMED");
        // generation 取快照当前权威 version_no，不用登记时冻结值
        assertThat(seen.get().generation()).isEqualTo(3L);
        assertThat(seen.get().snapshotId()).isEqualTo(snapshot);
    }

    @Test
    void retryableFailureBacksOffThenFailsAtLimit() {
        String snapshot = seedIntent();
        when(handler.handle(org.mockito.ArgumentMatchers.any()))
                .thenReturn(WikiIngestIntentOutcome.RETRYABLE_FAILURE);

        dispatcher.dispatchDue();
        assertThat(statusOf(snapshot)).isEqualTo("RETRY_WAIT");
        assertThat(jdbcTemplate.queryForObject("""
                select next_attempt_at is not null from source_ready_intent where source_snapshot_id = ?
                """, Boolean.class, snapshot)).isTrue();

        // 到期重试直到达上限：毒性意图终态化，不阻塞后续
        for (int round = 0; round < 5; round++) {
            jdbcTemplate.update("""
                    update source_ready_intent set next_attempt_at = null
                    where source_snapshot_id = ?
                    """, snapshot);
            dispatcher.dispatchDue();
        }
        assertThat(statusOf(snapshot)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("""
                select last_error_code from source_ready_intent where source_snapshot_id = ?
                """, String.class, snapshot)).isEqualTo("RETRYABLE_FAILURE");
    }

    @Test
    void staleByHandlerIsTerminalWithReason() {
        String snapshot = seedIntent();
        when(handler.handle(org.mockito.ArgumentMatchers.any()))
                .thenReturn(WikiIngestIntentOutcome.IGNORED_STALE);

        dispatcher.dispatchDue();

        assertThat(statusOf(snapshot)).isEqualTo("IGNORED_STALE");
    }

    @Test
    void parkedOutcomeDelaysWithoutConsumingRetryBudget() {
        // AU-003：功能关闭返回 PARKED——意图回到 PENDING、attempt 清零、长延迟再投，
        // 不会像 RETRYABLE_FAILURE 一样耗尽预算进 FAILED，重新启用后积压继续
        String snapshot = seedIntent();
        when(handler.handle(org.mockito.ArgumentMatchers.any()))
                .thenReturn(WikiIngestIntentOutcome.PARKED);

        dispatcher.dispatchDue();

        assertThat(statusOf(snapshot)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "select attempt_count from source_ready_intent where source_snapshot_id = ?",
                Integer.class, snapshot)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select next_attempt_at is not null from source_ready_intent where source_snapshot_id = ?",
                Boolean.class, snapshot)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select last_error_code from source_ready_intent where source_snapshot_id = ?",
                String.class, snapshot)).isEqualTo("WIKI_DISABLED");

        // 反例：同样轮次下 RETRYABLE_FAILURE 已进 RETRY_WAIT 且 attempt 递增，PARKED 不消耗预算
        when(handler.handle(org.mockito.ArgumentMatchers.any()))
                .thenReturn(WikiIngestIntentOutcome.APPLIED);
        jdbcTemplate.update(
                "update source_ready_intent set next_attempt_at = null where source_snapshot_id = ?",
                snapshot);
        dispatcher.dispatchDue();
        assertThat(statusOf(snapshot)).isEqualTo("CONSUMED");
    }

    @Test
    void missingHandlerBeanIsObservableRetryNotSilentSuccess() {
        String snapshot = seedIntent();
        SourceReadyIntentDispatcher missing = new SourceReadyIntentDispatcher(
                intents, emptyProvider(), jdbcTemplate, new SimpleMeterRegistry(),
                true, 20, 120, 600, 200, "1");

        missing.dispatchDue();

        assertThat(statusOf(snapshot)).isEqualTo("RETRY_WAIT");
        assertThat(jdbcTemplate.queryForObject("""
                select last_error_code from source_ready_intent where source_snapshot_id = ?
                """, String.class, snapshot)).isEqualTo("HANDLER_UNAVAILABLE");
    }

    @Test
    void disabledDispatcherLeavesIntentsPendingAsPause() {
        String snapshot = seedIntent();
        SourceReadyIntentDispatcher paused = new SourceReadyIntentDispatcher(
                intents, provider(handler), jdbcTemplate, new SimpleMeterRegistry(),
                false, 20, 120, 600, 200, "1");

        paused.dispatchDue();

        // 暂停不是消费：意图保持 PENDING，重新启用后可恢复（FB-02.6）
        assertThat(statusOf(snapshot)).isEqualTo("PENDING");
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<WikiReadyIngestIntentHandler> emptyProvider() {
        ObjectProvider<WikiReadyIngestIntentHandler> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
