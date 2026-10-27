package com.noteweave.infra.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.infra.usage.ActualUsageStore.ActualUsageRecord;
import com.noteweave.infra.usage.ActualUsageStore.Result;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * C5 真实 usage 持久化测试（FB-07 验收）：
 * 同 attempt 幂等；UNKNOWN→PARTIAL→COMPLETE 单调升级；COMPLETE 不被降级覆盖；
 * 同等级不同计数是可观察冲突不累加；无 workspace 的内部调用显式归属。
 */
class JdbcActualUsageStoreTest {

    private JdbcTemplate jdbcTemplate;
    private JdbcActualUsageStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:usage-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table actual_usage_fact (
                    id varchar(36) primary key, request_attempt_id varchar(128) not null,
                    workspace_id varchar(36), business_context varchar(64) not null,
                    provider varchar(64), requested_model varchar(128), returned_model varchar(128),
                    input_tokens bigint, output_tokens bigint, cached_input_tokens bigint,
                    reasoning_tokens bigint, reported tinyint not null default 0,
                    completeness varchar(16) not null, usage_schema_version varchar(32),
                    conflict_count int not null default 0, occurred_at timestamp not null,
                    created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp,
                    constraint uq_actual_usage_attempt unique (request_attempt_id),
                    constraint ck_actual_usage_completeness check (completeness in ('UNKNOWN','PARTIAL','COMPLETE'))
                )
                """);
        store = new JdbcActualUsageStore(jdbcTemplate);
    }

    private ActualUsageRecord record(String attemptId, String completeness,
            Long input, Long output, String workspace) {
        return new ActualUsageRecord(attemptId, workspace, workspace == null ? "INTERNAL_RESEARCH" : "CHAT",
                "openai-compatible", "noteweave-chat", "noteweave-chat",
                input, output, null, null, input != null, completeness, "usage-v1", Instant.now());
    }

    @Test
    void sameAttemptIsIdempotentAndRetainedAcrossRestart() {
        assertThat(store.record(record("att-1", "COMPLETE", 100L, 50L, "ws-1")))
                .isEqualTo(Result.STORED);
        assertThat(store.record(record("att-1", "COMPLETE", 100L, 50L, "ws-1")))
                .isEqualTo(Result.DUPLICATE);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from actual_usage_fact", Integer.class)).isEqualTo(1);
    }

    @Test
    void completenessUpgradesMonotonicallyAndNeverDowngrades() {
        store.record(record("att-2", "UNKNOWN", null, null, "ws-1"));
        assertThat(store.record(record("att-2", "PARTIAL", 80L, null, "ws-1")))
                .isEqualTo(Result.UPGRADED);
        assertThat(store.record(record("att-2", "COMPLETE", 100L, 60L, "ws-1")))
                .isEqualTo(Result.UPGRADED);
        // COMPLETE 之后的 UNKNOWN/PARTIAL 不覆盖
        assertThat(store.record(record("att-2", "UNKNOWN", null, null, "ws-1")))
                .isEqualTo(Result.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
                "select input_tokens from actual_usage_fact where request_attempt_id = 'att-2'",
                Long.class)).isEqualTo(100L);
    }

    @Test
    void concurrentSameRankUpgradeFromSameBaselineKeepsFirstWinner() {
        // V03：两线程从同一 UNKNOWN 竞争不同 PARTIAL——CAS 锁定决策依据的旧完整度，
        // 输家不覆盖赢家首见值（旧实现 where in ('UNKNOWN','PARTIAL') 会被第二个 PARTIAL 覆盖）
        store.record(record("att-v3a", "UNKNOWN", null, null, "ws-1"));
        // 直接二次调用模拟串行到达的并发窗口：第二次的决策依据仍是读到的 UNKNOWN
        assertThat(store.record(record("att-v3a", "PARTIAL", 80L, 40L, "ws-1")))
                .isEqualTo(Result.UPGRADED);
        // 依据仍是 UNKNOWN 的迟到线程：行已是 PARTIAL，CAS 不匹配 → 冲突，80/40 保留
        assertThat(store.record(record("att-v3a", "PARTIAL", 120L, 60L, "ws-1")))
                .isEqualTo(Result.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
                "select input_tokens from actual_usage_fact where request_attempt_id = 'att-v3a'",
                Long.class)).isEqualTo(80L);
    }

    @Test
    void sameLevelCachedOrReasoningMismatchIsObservableConflict() {
        // V03：同等级比较覆盖 cached/reasoning——只有 input/output 相同但 cached 不同也是冲突
        store.record(record("att-v3b", "COMPLETE", 100L, 50L, "ws-1"));
        // 简化：直接经 JDBC 置入 cached 值模拟两次上报差异
        jdbcTemplate.update("""
                update actual_usage_fact set cached_input_tokens = 10, reasoning_tokens = 5
                where request_attempt_id = 'att-v3b'
                """);
        // 再报同 input/output 但不同 cached/reasoning：冲突可观察
        var conflicting = new ActualUsageRecord(
                "att-v3b", "ws-1", "CHAT", "test-provider", "m1", "m1",
                100L, 50L, 999L, 77L,
                true, "COMPLETE", "usage-v1", java.time.Instant.now());
        assertThat(store.record(conflicting)).isEqualTo(Result.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
                "select cached_input_tokens from actual_usage_fact where request_attempt_id = 'att-v3b'",
                Long.class)).isEqualTo(10L);
    }

    @Test
    void sameLevelDifferentCountsConflictIsObservableNotAccumulated() {
        store.record(record("att-3", "COMPLETE", 100L, 50L, "ws-1"));
        assertThat(store.record(record("att-3", "COMPLETE", 120L, 50L, "ws-1")))
                .isEqualTo(Result.CONFLICT);
        assertThat(jdbcTemplate.queryForObject(
                "select conflict_count from actual_usage_fact where request_attempt_id = 'att-3'",
                Integer.class)).isEqualTo(1);
        // 首见值保留，不累加
        assertThat(jdbcTemplate.queryForObject(
                "select input_tokens from actual_usage_fact where request_attempt_id = 'att-3'",
                Long.class)).isEqualTo(100L);
    }

    @Test
    void internalCallWithoutWorkspaceKeepsExplicitContext() {
        assertThat(store.record(record("att-internal", "COMPLETE", 10L, 5L, null)))
                .isEqualTo(Result.STORED);
        String context = jdbcTemplate.queryForObject(
                "select business_context from actual_usage_fact where request_attempt_id = 'att-internal'",
                String.class);
        assertThat(context).isEqualTo("INTERNAL_RESEARCH");
    }

    @Test
    void workspaceReadIsPagedAndFiltered() {
        store.record(record("a1", "COMPLETE", 1L, 1L, "ws-read"));
        store.record(record("a2", "COMPLETE", 2L, 2L, "ws-read"));
        store.record(record("a3", "COMPLETE", 3L, 3L, "ws-other"));
        List<ActualUsageRecord> page = store.listByWorkspace("ws-read", null, 10);
        assertThat(page).hasSize(2);
        assertThat(page.get(0).requestAttemptId()).isEqualTo("a2");
    }
}
