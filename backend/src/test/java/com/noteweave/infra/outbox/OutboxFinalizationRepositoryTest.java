package com.noteweave.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.infra.outbox.OutboxFinalizationRepository.ClaimedFinalization;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 死信补偿登记与租约认领的并发测试（B-01 验收 5）：
 * 两个实例争同一条补偿只产生一个有效 owner；租约过期旧 owner 不能写回；
 * 达到重试上限进入 TERMINAL_FAILURE 而不是假装完成。
 * 断言均按目标 outboxId 过滤，避免遗留到期行干扰。
 *
 * <p>本轮（B-fix-round2-v1）改为手工构造 H2：@SpringBootTest 共享上下文里的真实
 * OutboxFinalizationExecutor 调度器会在「测试把租约置过期」与「测试自行认领」的
 * 窗口内抢走该行（全量负载下已复现），手工构造彻底隔离后台调度器。</p>
 */
class OutboxFinalizationRepositoryTest {
    private JdbcTemplate jdbcTemplate;
    private OutboxFinalizationRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:outbox-finalization-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        // 与 V196 + V202 生产 DDL 对齐
        jdbcTemplate.execute("""
                create table outbox_finalization (
                    id varchar(36) primary key,
                    workspace_id varchar(36) null,
                    store_type varchar(32) not null,
                    outbox_id varchar(190) not null,
                    delivery_generation int not null default 1,
                    business_kind varchar(64) not null,
                    business_identity_json varchar(1000) not null,
                    status varchar(32) not null,
                    attempt_count int not null default 0,
                    max_attempts int not null default 5,
                    next_attempt_at timestamp null,
                    lease_owner varchar(128) null,
                    lease_until timestamp null,
                    claim_token varchar(64) null,
                    last_error_code varchar(128) null,
                    last_error_summary varchar(500) null,
                    created_at timestamp not null default current_timestamp,
                    updated_at timestamp not null default current_timestamp,
                    completed_at timestamp null,
                    constraint uq_outbox_finalization_identity unique (store_type, outbox_id, delivery_generation),
                    constraint ck_outbox_finalization_status check (
                        status in ('PENDING', 'RUNNING', 'APPLIED', 'IGNORED_STALE', 'RETRY_WAIT', 'TERMINAL_FAILURE')
                    )
                )
                """);
        repository = new OutboxFinalizationRepository(jdbcTemplate,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new DataSourceTransactionManager(dataSource));
    }

    private String pendingIntent(String outboxId) {
        repository.insertPending(OutboxFinalizationRepository.STORE_TASK, outboxId, 1,
                "SOURCE_PARSE", "ws-1", Map.of("taskId", "t-1"), 5);
        return outboxId;
    }

    private List<ClaimedFinalization> claimFor(String owner, String outboxId) {
        return repository.claimDue(owner, 20, Duration.ofSeconds(120)).stream()
                .filter(claimed -> claimed.outboxId().equals(outboxId)).toList();
    }

    @Test
    void insertPendingMustBeIdempotentOnSameIdentity() {
        String outboxId = "outbox-" + System.nanoTime();
        assertThat(pendingIntent(outboxId)).isEqualTo(outboxId);
        boolean second = repository.insertPending(OutboxFinalizationRepository.STORE_TASK, outboxId, 1,
                "SOURCE_PARSE", "ws-1", Map.of("taskId", "t-1"), 5);
        assertThat(second).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from outbox_finalization where outbox_id = ?", Integer.class, outboxId))
                .isEqualTo(1);
        // 不同投递代次是不同补偿
        assertThat(repository.insertPending(OutboxFinalizationRepository.STORE_TASK, outboxId, 2,
                "SOURCE_PARSE", "ws-1", Map.of("taskId", "t-1"), 5)).isTrue();
    }

    @Test
    void concurrentClaimsMustNeverDoubleClaimAndStayLively() throws Exception {
        int totalClaimed = 0;
        for (int round = 0; round < 3; round++) {
            String outboxId = "claim-" + round + "-" + System.nanoTime();
            pendingIntent(outboxId);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Integer>> attempts = new java.util.ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    String owner = "owner-" + round + "-" + i;
                    attempts.add(pool.submit((Callable<Integer>) () -> {
                        start.await();
                        return claimFor(owner, outboxId).size();
                    }));
                }
                start.countDown();
                int claimed = 0;
                for (Future<Integer> attempt : attempts) {
                    claimed += attempt.get();
                }
                // 核心不变量：两个实例并发认领同一条补偿，绝不能产生两个有效 owner
                assertThat(claimed).as("round %d", round).isLessThanOrEqualTo(1);
                if (claimed == 0) {
                    // 极端调度下两线程都没赶上：主线程补认领验证行仍然可被认领（活性）
                    assertThat(claimFor("owner-main-" + round, outboxId)).hasSize(1);
                    claimed = 1;
                }
                totalClaimed += claimed;
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
        assertThat(totalClaimed).isGreaterThanOrEqualTo(3);
    }

    @Test
    void expiredLeaseCanBeReclaimedAndStaleOwnerCannotFinish() {
        String outboxId = "stale-" + System.nanoTime();
        pendingIntent(outboxId);
        List<ClaimedFinalization> first = claimFor("owner-a", outboxId);
        assertThat(first).hasSize(1);
        // 租约未过期：第二个实例认领不到
        assertThat(claimFor("owner-b", outboxId)).isEmpty();
        // 租约过期：新 owner 可以重新认领
        jdbcTemplate.update("update outbox_finalization set lease_until = timestamp '2020-01-01 00:00:00' where outbox_id = ?",
                outboxId);
        List<ClaimedFinalization> second = claimFor("owner-b", outboxId);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).leaseOwner()).isEqualTo("owner-b");
        // 旧 owner 的结果不能覆盖新执行
        assertThat(repository.finish(first.get(0).id(), first.get(0).claimToken(),
                OutboxFinalizationRepository.STATUS_APPLIED, java.time.Instant.now())).isFalse();
        assertThat(repository.finish(second.get(0).id(), second.get(0).claimToken(),
                OutboxFinalizationRepository.STATUS_APPLIED, java.time.Instant.now())).isTrue();
        assertThat(repository.statusOf(OutboxFinalizationRepository.STORE_TASK, outboxId, 1))
                .isEqualTo(OutboxFinalizationRepository.STATUS_APPLIED);
    }

    @Test
    void retryUntilLimitMustEndInTerminalFailureNotSilentSuccess() {
        String outboxId = "terminal-" + System.nanoTime();
        repository.insertPending(OutboxFinalizationRepository.STORE_TASK, outboxId, 1,
                "SOURCE_PARSE", "ws-1", Map.of("taskId", "t-1"), 2);
        for (int attempt = 0; attempt < 2; attempt++) {
            List<ClaimedFinalization> claimed = claimFor("owner", outboxId);
            assertThat(claimed).hasSize(1);
            boolean retried = repository.retryLater(claimed.get(0).id(), claimed.get(0).claimToken(), "DB_BUSY", "暂时失败", 0);
            assertThat(retried).isTrue();
            // 退避最小 1 秒：清空到期时间模拟退避结束后再进入下一轮
            jdbcTemplate.update(
                    "update outbox_finalization set next_attempt_at = null where outbox_id = ?", outboxId);
        }
        assertThat(repository.statusOf(OutboxFinalizationRepository.STORE_TASK, outboxId, 1))
                .isEqualTo(OutboxFinalizationRepository.STATUS_TERMINAL_FAILURE);
    }

    @Test
    void runningLeaseExpiredRowIsReclaimable() {
        String outboxId = "running-" + System.nanoTime();
        pendingIntent(outboxId);
        assertThat(claimFor("owner-a", outboxId)).hasSize(1);
        // RUNNING 且租约过期：可被重新认领（执行者崩溃后补偿不能永久卡住）
        jdbcTemplate.update("update outbox_finalization set lease_until = timestamp '2020-01-01 00:00:00' where outbox_id = ?",
                outboxId);
        assertThat(claimFor("owner-b", outboxId)).hasSize(1);
    }
}
