package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * MA4G §16.6 real-MySQL lock-order proof.
 *
 * <p>This class deliberately ends in {@code IT}, so the ordinary Surefire
 * unit-test discovery does not run it against H2. The isolated LockMatrix
 * Compose round invokes it explicitly and supplies MySQL 8.4. All gates,
 * triggers and evidence tables are test-only and are removed after the run.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "MA4G_LOCK_MATRIX_ENABLED", matches = "true")
class ResearchAgentMySqlLockMatrixIT {

    private static final String CONTROL_TABLE = "ma4g_lock_matrix_control";
    private static final String MUTEX_TABLE = "ma4g_lock_matrix_mutex";
    private static final String TASK_UPDATE_TRIGGER = "ma4g_lock_matrix_task_bu";
    private static final String RUN_UPDATE_TRIGGER = "ma4g_lock_matrix_run_bu";
    private static final String TASK_INSERT_TRIGGER = "ma4g_lock_matrix_task_bi";
    private static final Duration GRAPH_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CASE_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<String> BUDGET_KEYS = Set.of(
            "llm_calls", "search_calls", "fetch_calls", "read_calls", "extract_calls",
            "evidence_cards", "evidence_appended", "candidates_submitted",
            "candidate_merges_accepted", "candidate_merges_rejected");
    private static final List<String> EXPECTED_EVIDENCE_CASES = List.of(
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L1", "L2");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ResearchAgentTaskService taskService;
    @Autowired private ResearchAgentLifecycleService lifecycleService;
    @Autowired private ResearchAgentTaskCoordinatorService coordinatorService;
    @Autowired private ResearchAgentCommandOutboxService outboxService;
    @Autowired private ResearchBudgetAndCheckpointService budgetService;
    @Autowired private ResearchAgentCompletionService completionService;
    @Autowired private ResearchAgentCompletionCanonicalizer canonicalizer;

    private final Map<String, Map<String, Object>> evidenceByCase = new LinkedHashMap<>();
    private Path evidenceDirectory;
    private String rootJdbcUrl;
    private String rootUser;
    private String rootPassword;
    private Map<String, Object> databaseIdentity;

    @BeforeAll
    void installTestOnlyLockHarness() throws Exception {
        rootJdbcUrl = requiredEnvironment("MA4G_LOCK_MATRIX_ROOT_JDBC_URL");
        rootUser = requiredEnvironment("MA4G_LOCK_MATRIX_ROOT_USER");
        rootPassword = requiredEnvironment("MA4G_LOCK_MATRIX_ROOT_PASSWORD");
        evidenceDirectory = Path.of(requiredEnvironment("MA4G_LOCK_MATRIX_EVIDENCE_DIR")).toAbsolutePath();
        Files.createDirectories(evidenceDirectory);

        assertThat(dataSource.getClass().getName()).containsIgnoringCase("Hikari");
        databaseIdentity = databaseIdentity();
        assertThat(String.valueOf(databaseIdentity.get("product"))).isEqualTo("MySQL");
        assertThat(String.valueOf(databaseIdentity.get("version"))).startsWith("8.4");
        assertThat(String.valueOf(databaseIdentity.get("transaction_isolation"))).isEqualTo("READ-COMMITTED");
        assertThat(String.valueOf(databaseIdentity.get("character_set_server"))).isEqualTo("utf8mb4");
        assertThat(String.valueOf(databaseIdentity.get("collation_server"))).isEqualTo("utf8mb4_unicode_ci");
        assertThat(String.valueOf(databaseIdentity.get("performance_schema"))).isEqualTo("1");
        assertThat(lockMetrics().keySet()).containsExactlyInAnyOrder("lock_deadlocks", "lock_timeouts");

        dropTestOnlyLockHarness();
        jdbcTemplate.execute("create table " + MUTEX_TABLE + " (id int primary key, token int not null) engine=InnoDB");
        jdbcTemplate.execute("""
                create table ma4g_lock_matrix_control (
                    id int primary key,
                    case_id varchar(16) not null,
                    armed tinyint not null,
                    leader_action varchar(32) not null,
                    target_run_id varchar(36) null,
                    target_task_id varchar(36) null
                ) engine=InnoDB
                """);
        jdbcTemplate.update("insert into " + MUTEX_TABLE + "(id, token) values (1, 0)");
        jdbcTemplate.update("""
                insert into ma4g_lock_matrix_control(
                    id, case_id, armed, leader_action, target_run_id, target_task_id
                ) values (1, 'NONE', 0, 'NONE', null, null)
                """);
        installTaskUpdateTrigger();
        installRunUpdateTrigger();
        installTaskInsertTrigger();
        writeJson("database-identity.json", databaseIdentity);
    }

    @BeforeEach
    void disarmBeforeCase() {
        disarm();
    }

    @AfterAll
    void removeTestOnlyLockHarnessAndWriteSummary() throws Exception {
        Throwable cleanupFailure = null;
        try {
            disarm();
            dropTestOnlyLockHarness();
        } catch (Throwable failure) {
            cleanupFailure = failure;
        }

        List<String> verified = evidenceByCase.entrySet().stream()
                .filter(entry -> "VERIFIED".equals(entry.getValue().get("status")))
                .map(Map.Entry::getKey)
                .toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schema_version", "noteweave-ma4g-lock-matrix-evidence.v1");
        summary.put("status", verified.containsAll(EXPECTED_EVIDENCE_CASES)
                && EXPECTED_EVIDENCE_CASES.containsAll(verified)
                && cleanupFailure == null ? "VERIFIED" : "FAILED");
        summary.put("expected_cases", EXPECTED_EVIDENCE_CASES);
        summary.put("verified_cases", verified);
        summary.put("database", databaseIdentity);
        summary.put("hikari_datasource_class", dataSource.getClass().getName());
        summary.put("test_only_harness_removed", cleanupFailure == null);
        if (cleanupFailure != null) summary.put("cleanup_failure", cleanupFailure.toString());
        writeJson("summary.json", summary);
        if (cleanupFailure != null) throw new AssertionError("LockMatrix test-only harness cleanup failed", cleanupFailure);
    }

    @Test
    void caseACompletionLinearizesBeforeCancel() throws Exception {
        AtomicFixture fixture = atomicFixture(true);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture);
        RaceResult result = executeCase("A", "COMPLETE", fixture.runId(), fixture.taskId(),
                () -> completionService.complete(fixture.taskId(), envelope),
                () -> lifecycleService.cancelRun(fixture.runId(), "LOCK_MATRIX_A"));

        assertThat(success(result.leader(), ResearchAgentCompletionReceipt.class).idempotentReplay()).isFalse();
        assertThat(success(result.follower(), ResearchAgentLifecycleService.CancelReceipt.class))
                .isEqualTo(new ResearchAgentLifecycleService.CancelReceipt(0, false));
        assertCompleteState(fixture);
        assertThat(runStatus(fixture.runId())).isEqualTo("CANCELLED");
        markVerified("A");
    }

    @Test
    void caseBCancelLinearizesBeforeCompletion() throws Exception {
        AtomicFixture fixture = atomicFixture(true);
        RaceResult result = executeCase("B", "CANCEL", fixture.runId(), fixture.taskId(),
                () -> lifecycleService.cancelRun(fixture.runId(), "LOCK_MATRIX_B"),
                () -> completionService.complete(fixture.taskId(), envelope(fixture)));

        assertThat(success(result.leader(), ResearchAgentLifecycleService.CancelReceipt.class))
                .isEqualTo(new ResearchAgentLifecycleService.CancelReceipt(1, false));
        assertBusiness(result.follower(), "RESEARCH_AGENT_RUN_TERMINAL");
        assertCancelledState(fixture);
        markVerified("B");
    }

    @Test
    void caseCExpireLinearizesBeforeCompletionAndCannotTouchSubmittedTask() throws Exception {
        AtomicFixture fixture = expiredAtomicFixture();
        RaceResult result = executeCase("C", "EXPIRE", fixture.runId(), fixture.taskId(),
                () -> taskService.expireLeases(),
                () -> completionService.complete(fixture.taskId(), envelope(fixture)));

        assertThat(success(result.leader(), Integer.class)).isEqualTo(1);
        assertBusiness(result.follower(), "RESEARCH_AGENT_TASK_STALE_LEASE");
        assertThat(taskStatus(fixture.taskId())).isEqualTo("EXPIRED");
        assertThat(reservationState(fixture.taskId())).isEqualTo("RESERVED");
        assertThat(completionCount(fixture.taskId())).isZero();

        AtomicFixture submitted = atomicFixture(true);
        completionService.complete(submitted.taskId(), envelope(submitted));
        Map<String, Object> submittedState = fullState(submitted.runId());
        assertThat(taskService.expireLeases()).isZero();
        assertThat(fullState(submitted.runId())).isEqualTo(submittedState);
        markVerified("C");
    }

    @Test
    void caseDReaperLinearizesBeforeCompletionAndCannotTouchSubmittedTask() throws Exception {
        AtomicFixture fixture = expiredAtomicFixture();
        RaceResult result = executeCase("D", "REAPER", fixture.runId(), fixture.taskId(),
                () -> lifecycleService.reapExpiredLeases(),
                () -> completionService.complete(fixture.taskId(), envelope(fixture)));

        assertThat(success(result.leader(), ResearchAgentLifecycleService.ReapReceipt.class))
                .isEqualTo(new ResearchAgentLifecycleService.ReapReceipt(1, 0));
        assertBusiness(result.follower(), "RESEARCH_AGENT_TASK_STALE_LEASE");
        assertThat(taskStatus(fixture.taskId())).isEqualTo("RETRY_WAIT");
        assertThat(reservationState(fixture.taskId())).isEqualTo("RESERVED");
        assertThat(completionCount(fixture.taskId())).isZero();

        AtomicFixture submitted = atomicFixture(true);
        completionService.complete(submitted.taskId(), envelope(submitted));
        Map<String, Object> submittedState = fullState(submitted.runId());
        assertThat(lifecycleService.reapExpiredLeases())
                .isEqualTo(new ResearchAgentLifecycleService.ReapReceipt(0, 0));
        assertThat(fullState(submitted.runId())).isEqualTo(submittedState);
        markVerified("D");
    }

    @Test
    void caseEClaimLinearizesBeforeCancel() throws Exception {
        AtomicFixture fixture = atomicFixture(false);
        RaceResult result = executeCase("E", "CLAIM", fixture.runId(), fixture.taskId(),
                () -> taskService.claimTask(new ResearchAgentTaskService.ClaimCommand(
                        fixture.taskId(), "worker-a", 300)),
                () -> lifecycleService.cancelRun(fixture.runId(), "LOCK_MATRIX_E"));

        success(result.leader(), ResearchAgentTaskService.ClaimedTask.class);
        assertThat(success(result.follower(), ResearchAgentLifecycleService.CancelReceipt.class).cancelledTaskCount())
                .isEqualTo(1);
        assertCancelledState(fixture);
        markVerified("E");
    }

    @Test
    void caseFCancelLinearizesBeforeClaim() throws Exception {
        AtomicFixture fixture = atomicFixture(false);
        RaceResult result = executeCase("F", "CANCEL", fixture.runId(), fixture.taskId(),
                () -> lifecycleService.cancelRun(fixture.runId(), "LOCK_MATRIX_F"),
                () -> taskService.claimTask(new ResearchAgentTaskService.ClaimCommand(
                        fixture.taskId(), "worker-a", 300)));

        assertThat(success(result.leader(), ResearchAgentLifecycleService.CancelReceipt.class).cancelledTaskCount())
                .isEqualTo(1);
        assertBusiness(result.follower(), "RESEARCH_AGENT_TASK_NOT_CLAIMABLE");
        assertCancelledState(fixture);
        markVerified("F");
    }

    @Test
    void caseGHeartbeatLinearizesBeforeCancel() throws Exception {
        AtomicFixture fixture = atomicFixture(true);
        RaceResult result = executeCase("G", "HEARTBEAT", fixture.runId(), fixture.taskId(),
                () -> taskService.heartbeat(new ResearchAgentTaskService.LeaseCommand(
                        fixture.taskId(), "worker-a", fixture.leaseEpoch(), fixture.fencingToken(), 300)),
                () -> lifecycleService.cancelRun(fixture.runId(), "LOCK_MATRIX_G"));

        success(result.leader(), ResearchAgentTaskService.ClaimedTask.class);
        assertThat(success(result.follower(), ResearchAgentLifecycleService.CancelReceipt.class).cancelledTaskCount())
                .isEqualTo(1);
        assertCancelledState(fixture);
        markVerified("G");
    }

    @Test
    void caseHCancelLinearizesBeforeHeartbeat() throws Exception {
        AtomicFixture fixture = atomicFixture(true);
        RaceResult result = executeCase("H", "CANCEL", fixture.runId(), fixture.taskId(),
                () -> lifecycleService.cancelRun(fixture.runId(), "LOCK_MATRIX_H"),
                () -> taskService.heartbeat(new ResearchAgentTaskService.LeaseCommand(
                        fixture.taskId(), "worker-a", fixture.leaseEpoch(), fixture.fencingToken(), 300)));

        assertThat(success(result.leader(), ResearchAgentLifecycleService.CancelReceipt.class).cancelledTaskCount())
                .isEqualTo(1);
        assertBusiness(result.follower(), "RESEARCH_AGENT_TASK_STALE_LEASE");
        assertCancelledState(fixture);
        markVerified("H");
    }

    @Test
    void caseICoordinatorLinearizesBeforeCancel() throws Exception {
        CoordinatorFixture fixture = coordinatorFixture();
        RaceResult result = executeCase("I", "COORDINATOR", fixture.runId(), null,
                () -> coordinatorService.planAndEnqueue(fixture.runId()),
                () -> lifecycleService.cancelRun(fixture.runId(), "LOCK_MATRIX_I"));

        ResearchAgentTaskCoordinatorService.CoordinatorReceipt receipt = success(
                result.leader(), ResearchAgentTaskCoordinatorService.CoordinatorReceipt.class);
        assertThat(receipt.createdTaskCount()).isEqualTo(1);
        assertThat(success(result.follower(), ResearchAgentLifecycleService.CancelReceipt.class).cancelledTaskCount())
                .isEqualTo(1);
        assertThat(runStatus(fixture.runId())).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_task where research_run_id = ? and status = 'CANCELLED'",
                Integer.class, fixture.runId())).isEqualTo(1);
        assertRunReservations(fixture.runId());
        markVerified("I");
    }

    @Test
    void caseJCancelLinearizesBeforeCoordinator() throws Exception {
        CoordinatorFixture fixture = coordinatorFixture();
        RaceResult result = executeCase("J", "CANCEL", fixture.runId(), null,
                () -> lifecycleService.cancelRun(fixture.runId(), "LOCK_MATRIX_J"),
                () -> coordinatorService.planAndEnqueue(fixture.runId()));

        assertThat(success(result.leader(), ResearchAgentLifecycleService.CancelReceipt.class).cancelledTaskCount())
                .isZero();
        assertBusiness(result.follower(), "RESEARCH_AGENT_RUN_TERMINAL");
        assertThat(runStatus(fixture.runId())).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_task where research_run_id = ?",
                Integer.class, fixture.runId())).isZero();
        markVerified("J");
    }

    @Test
    void caseKIdenticalCompletionsHaveOneCommitAndOneExactReplay() throws Exception {
        AtomicFixture fixture = atomicFixture(true);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture);
        RaceResult result = executeCase("K", "COMPLETE", fixture.runId(), fixture.taskId(),
                () -> completionService.complete(fixture.taskId(), envelope),
                () -> completionService.complete(fixture.taskId(), envelope));

        ResearchAgentCompletionReceipt leader = success(result.leader(), ResearchAgentCompletionReceipt.class);
        ResearchAgentCompletionReceipt follower = success(result.follower(), ResearchAgentCompletionReceipt.class);
        assertThat(leader.idempotentReplay()).isFalse();
        assertThat(follower.idempotentReplay()).isTrue();
        assertThat(follower.completionId()).isEqualTo(leader.completionId());
        assertThat(follower.receiptDigest()).isEqualTo(leader.receiptDigest());
        assertCompleteState(fixture);

        Map<String, Object> committedState = fullState(fixture.runId());
        assertThat(completionService.complete(fixture.taskId(), envelope).idempotentReplay()).isTrue();
        assertThat(fullState(fixture.runId())).isEqualTo(committedState);
        markVerified("K");
    }

    @Test
    void caseLConflictingCompletionsChooseTheGateLeaderInBothOrders() throws Exception {
        AtomicFixture firstFixture = atomicFixture(true);
        ResearchAgentCompletionEnvelope first = envelope(firstFixture);
        ResearchAgentCompletionEnvelope second = conflicting(first);
        RaceResult firstOrder = executeCase("L1", "COMPLETE", firstFixture.runId(), firstFixture.taskId(),
                () -> completionService.complete(firstFixture.taskId(), first),
                () -> completionService.complete(firstFixture.taskId(), second));
        success(firstOrder.leader(), ResearchAgentCompletionReceipt.class);
        assertBusiness(firstOrder.follower(), "RESEARCH_AGENT_COMPLETION_IDEMPOTENCY_CONFLICT");
        assertCompleteState(firstFixture);
        Map<String, Object> firstWinnerState = fullState(firstFixture.runId());
        assertBusiness(capture(() -> completionService.complete(firstFixture.taskId(), second)),
                "RESEARCH_AGENT_COMPLETION_IDEMPOTENCY_CONFLICT");
        assertThat(fullState(firstFixture.runId())).isEqualTo(firstWinnerState);
        markVerified("L1");

        AtomicFixture secondFixture = atomicFixture(true);
        ResearchAgentCompletionEnvelope original = envelope(secondFixture);
        ResearchAgentCompletionEnvelope modified = conflicting(original);
        RaceResult reverseOrder = executeCase("L2", "COMPLETE", secondFixture.runId(), secondFixture.taskId(),
                () -> completionService.complete(secondFixture.taskId(), modified),
                () -> completionService.complete(secondFixture.taskId(), original));
        success(reverseOrder.leader(), ResearchAgentCompletionReceipt.class);
        assertBusiness(reverseOrder.follower(), "RESEARCH_AGENT_COMPLETION_IDEMPOTENCY_CONFLICT");
        assertCompleteState(secondFixture);
        Map<String, Object> secondWinnerState = fullState(secondFixture.runId());
        assertBusiness(capture(() -> completionService.complete(secondFixture.taskId(), original)),
                "RESEARCH_AGENT_COMPLETION_IDEMPOTENCY_CONFLICT");
        assertThat(fullState(secondFixture.runId())).isEqualTo(secondWinnerState);
        markVerified("L2");
    }

    private RaceResult executeCase(
            String caseId,
            String leaderAction,
            String runId,
            String taskId,
            Callable<Object> leaderOperation,
            Callable<Object> followerOperation
    ) throws Exception {
        Map<String, Object> caseEvidence = new LinkedHashMap<>();
        caseEvidence.put("schema_version", "noteweave-ma4g-lock-matrix-case.v1");
        caseEvidence.put("case_id", caseId);
        caseEvidence.put("leader_action", leaderAction);
        caseEvidence.put("run_id", runId);
        caseEvidence.put("task_id", taskId);
        caseEvidence.put("status", "STARTED");
        caseEvidence.put("state_before", fullState(runId));
        evidenceByCase.put(caseId, caseEvidence);

        Map<String, Long> metricsBefore = lockMetrics();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Outcome> leaderFuture = null;
        Future<Outcome> followerFuture = null;
        boolean gateReleased = false;
        long started = System.nanoTime();
        try (Connection holder = rootConnection()) {
            arm(caseId, leaderAction, runId, taskId);
            holder.setAutoCommit(false);
            long holderConnectionId = connectionId(holder);
            try (Statement statement = holder.createStatement();
                 ResultSet ignored = statement.executeQuery("select token from " + MUTEX_TABLE + " where id = 1 for update")) {
                assertThat(ignored.next()).isTrue();
            }

            leaderFuture = executor.submit(() -> capture(leaderOperation));
            List<WaitEdge> gateGraph = awaitWaitGraph(edges -> edges.stream().anyMatch(edge ->
                    MUTEX_TABLE.equals(edge.objectName()) && edge.blockingProcessId() == holderConnectionId));
            WaitEdge gateEdge = gateGraph.stream().filter(edge -> MUTEX_TABLE.equals(edge.objectName())
                            && edge.blockingProcessId() == holderConnectionId)
                    .findFirst().orElseThrow();

            followerFuture = executor.submit(() -> capture(followerOperation));
            List<WaitEdge> fullGraph = awaitWaitGraph(edges -> edges.stream().anyMatch(edge ->
                    edge.blockingProcessId() == gateEdge.requestingProcessId()
                            && "research_run".equals(edge.objectName())));
            WaitEdge followerEdge = fullGraph.stream().filter(edge ->
                            edge.blockingProcessId() == gateEdge.requestingProcessId()
                                    && "research_run".equals(edge.objectName()))
                    .findFirst().orElseThrow();

            assertThat(gateEdge.blockingProcessId()).isEqualTo(holderConnectionId);
            assertThat(gateEdge.requestingUser()).isEqualTo("noteweave");
            assertThat(followerEdge.requestingUser()).isEqualTo("noteweave");
            assertThat(gateEdge.requestingProcessId())
                    .isNotEqualTo(followerEdge.requestingProcessId())
                    .isNotEqualTo(holderConnectionId);
            assertThat(followerEdge.requestingProcessId()).isNotEqualTo(holderConnectionId);

            caseEvidence.put("holder_connection_id", holderConnectionId);
            caseEvidence.put("leader_connection_id", gateEdge.requestingProcessId());
            caseEvidence.put("follower_connection_id", followerEdge.requestingProcessId());
            caseEvidence.put("leader_wait_graph", gateGraph);
            caseEvidence.put("full_wait_graph", fullGraph);
            caseEvidence.put("proved_chain", "follower -> leader -> gate-holder");

            holder.commit();
            gateReleased = true;
            Outcome leader = leaderFuture.get(CASE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Outcome follower = followerFuture.get(CASE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            Map<String, Long> metricsAfter = lockMetrics();

            assertThat(durationMillis).isLessThan(CASE_TIMEOUT.toMillis());
            assertNoDeadlockOrTimeout(leader);
            assertNoDeadlockOrTimeout(follower);
            assertThat(metricsAfter.get("lock_deadlocks")).isEqualTo(metricsBefore.get("lock_deadlocks"));
            assertThat(metricsAfter.get("lock_timeouts")).isEqualTo(metricsBefore.get("lock_timeouts"));

            assertRunInvariants(runId);
            caseEvidence.put("duration_ms", durationMillis);
            caseEvidence.put("leader_outcome", outcomeEvidence(leader));
            caseEvidence.put("follower_outcome", outcomeEvidence(follower));
            caseEvidence.put("lock_metrics_before", metricsBefore);
            caseEvidence.put("lock_metrics_after", metricsAfter);
            caseEvidence.put("state_after", fullState(runId));
            caseEvidence.put("status", "WAIT_GRAPH_VERIFIED");
            writeCaseEvidence(caseId);
            return new RaceResult(leader, follower);
        } catch (Throwable failure) {
            caseEvidence.put("status", "FAILED");
            caseEvidence.put("failure", failure.toString());
            caseEvidence.put("state_after_failure", fullStateSafely(runId));
            writeCaseEvidence(caseId);
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new AssertionError(failure);
        } finally {
            if (!gateReleased) {
                // Closing the holder connection rolls its transaction back and releases the mutex.
                if (leaderFuture != null) leaderFuture.cancel(true);
                if (followerFuture != null) followerFuture.cancel(true);
            }
            disarm();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private void installTaskUpdateTrigger() {
        executeAsRoot("""
                create trigger ma4g_lock_matrix_task_bu
                before update on research_agent_task
                for each row
                begin
                    declare v_armed int default 0;
                    declare v_action varchar(32) default 'NONE';
                    declare v_run_id varchar(36) default null;
                    declare v_task_id varchar(36) default null;
                    declare v_token int default 0;
                    select armed, leader_action, target_run_id, target_task_id
                      into v_armed, v_action, v_run_id, v_task_id
                      from ma4g_lock_matrix_control where id = 1;
                    if v_armed = 1 and v_run_id = old.research_run_id and v_task_id = old.id and (
                           (v_action = 'COMPLETE' and new.status = 'SUBMITTED')
                        or (v_action = 'EXPIRE' and new.status = 'EXPIRED')
                        or (v_action = 'REAPER' and new.status in ('RETRY_WAIT', 'FAILED'))
                        or (v_action = 'CLAIM' and new.status = 'CLAIMED')
                        or (v_action = 'HEARTBEAT' and new.status = 'RUNNING')
                    ) then
                        select token into v_token
                          from ma4g_lock_matrix_mutex where id = 1 for update;
                    end if;
                end
                """);
    }

    private void installRunUpdateTrigger() {
        executeAsRoot("""
                create trigger ma4g_lock_matrix_run_bu
                before update on research_run
                for each row
                begin
                    declare v_armed int default 0;
                    declare v_action varchar(32) default 'NONE';
                    declare v_run_id varchar(36) default null;
                    declare v_token int default 0;
                    select armed, leader_action, target_run_id
                      into v_armed, v_action, v_run_id
                      from ma4g_lock_matrix_control where id = 1;
                    if v_armed = 1 and v_action = 'CANCEL' and v_run_id = old.id
                       and new.status = 'CANCELLED' then
                        select token into v_token
                          from ma4g_lock_matrix_mutex where id = 1 for update;
                    end if;
                end
                """);
    }

    private void installTaskInsertTrigger() {
        executeAsRoot("""
                create trigger ma4g_lock_matrix_task_bi
                before insert on research_agent_task
                for each row
                begin
                    declare v_armed int default 0;
                    declare v_action varchar(32) default 'NONE';
                    declare v_run_id varchar(36) default null;
                    declare v_token int default 0;
                    select armed, leader_action, target_run_id
                      into v_armed, v_action, v_run_id
                      from ma4g_lock_matrix_control where id = 1;
                    if v_armed = 1 and v_action = 'COORDINATOR'
                       and v_run_id = new.research_run_id then
                        select token into v_token
                          from ma4g_lock_matrix_mutex where id = 1 for update;
                    end if;
                end
                """);
    }

    private void dropTestOnlyLockHarness() {
        executeAsRoot("drop trigger if exists " + TASK_UPDATE_TRIGGER);
        executeAsRoot("drop trigger if exists " + RUN_UPDATE_TRIGGER);
        executeAsRoot("drop trigger if exists " + TASK_INSERT_TRIGGER);
        jdbcTemplate.execute("drop table if exists " + CONTROL_TABLE);
        jdbcTemplate.execute("drop table if exists " + MUTEX_TABLE);
    }

    private void executeAsRoot(String sql) {
        try (Connection connection = rootConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("Test-only lock harness DDL failed", exception);
        }
    }

    private void arm(String caseId, String leaderAction, String runId, String taskId) {
        int updated = jdbcTemplate.update("""
                update ma4g_lock_matrix_control
                set case_id = ?, armed = 1, leader_action = ?, target_run_id = ?, target_task_id = ?
                where id = 1
                """, caseId, leaderAction, runId, taskId);
        assertThat(updated).isEqualTo(1);
    }

    private void disarm() {
        if (jdbcTemplate == null) return;
        try {
            jdbcTemplate.update("""
                    update ma4g_lock_matrix_control
                    set case_id = 'NONE', armed = 0, leader_action = 'NONE',
                        target_run_id = null, target_task_id = null
                    where id = 1
                    """);
        } catch (RuntimeException ignored) {
            // Before installation and after cleanup there is intentionally no control table.
        }
    }

    private List<WaitEdge> awaitWaitGraph(java.util.function.Predicate<List<WaitEdge>> condition)
            throws Exception {
        Instant deadline = Instant.now().plus(GRAPH_TIMEOUT);
        List<WaitEdge> last = List.of();
        while (Instant.now().isBefore(deadline)) {
            last = waitGraph();
            if (condition.test(last)) return last;
            Thread.sleep(50);
        }
        throw new AssertionError("Expected performance_schema wait graph was not observed; last=" + last);
    }

    private List<WaitEdge> waitGraph() throws SQLException {
        try (Connection connection = rootConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     select rw.requesting_thread_id,
                            coalesce(rt.processlist_id, 0) as requesting_process_id,
                            coalesce(rt.processlist_user, '') as requesting_user,
                            rw.blocking_thread_id,
                            coalesce(bt.processlist_id, 0) as blocking_process_id,
                            coalesce(bt.processlist_user, '') as blocking_user,
                            coalesce(rl.object_schema, '') as object_schema,
                            coalesce(rl.object_name, '') as object_name,
                            coalesce(rl.index_name, '') as index_name,
                            rl.lock_type, rl.lock_mode, rl.lock_status,
                            coalesce(rl.lock_data, '') as lock_data
                     from performance_schema.data_lock_waits rw
                     join performance_schema.data_locks rl
                       on rl.engine_lock_id = rw.requesting_engine_lock_id
                     left join performance_schema.threads rt
                       on rt.thread_id = rw.requesting_thread_id
                     left join performance_schema.threads bt
                       on bt.thread_id = rw.blocking_thread_id
                     where rl.object_schema = database()
                     order by requesting_process_id, blocking_process_id, object_name, index_name
                     """)) {
            List<WaitEdge> edges = new ArrayList<>();
            while (rs.next()) {
                edges.add(new WaitEdge(
                        rs.getLong("requesting_thread_id"), rs.getLong("requesting_process_id"),
                        rs.getString("requesting_user"), rs.getLong("blocking_thread_id"),
                        rs.getLong("blocking_process_id"), rs.getString("blocking_user"),
                        rs.getString("object_schema"), rs.getString("object_name"),
                        rs.getString("index_name"), rs.getString("lock_type"),
                        rs.getString("lock_mode"), rs.getString("lock_status"), rs.getString("lock_data")));
            }
            return List.copyOf(edges);
        }
    }

    private Map<String, Object> databaseIdentity() throws SQLException {
        try (Connection connection = rootConnection(); Statement statement = connection.createStatement()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("product", connection.getMetaData().getDatabaseProductName());
            value.put("version", connection.getMetaData().getDatabaseProductVersion());
            value.put("jdbc_url", redactedJdbcUrl(rootJdbcUrl));
            try (ResultSet rs = statement.executeQuery("""
                    select @@version as version,
                           @@transaction_isolation as transaction_isolation,
                           @@character_set_server as character_set_server,
                           @@collation_server as collation_server,
                           @@performance_schema as performance_schema,
                           database() as database_name
                    """)) {
                assertThat(rs.next()).isTrue();
                value.put("version", rs.getString("version"));
                value.put("transaction_isolation", rs.getString("transaction_isolation"));
                value.put("character_set_server", rs.getString("character_set_server"));
                value.put("collation_server", rs.getString("collation_server"));
                value.put("performance_schema", rs.getString("performance_schema"));
                value.put("database_name", rs.getString("database_name"));
            }
            return value;
        }
    }

    private Map<String, Long> lockMetrics() throws SQLException {
        try (Connection connection = rootConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     select name, count
                     from information_schema.innodb_metrics
                     where name in ('lock_deadlocks', 'lock_timeouts')
                     order by name
                     """)) {
            Map<String, Long> metrics = new LinkedHashMap<>();
            while (rs.next()) metrics.put(rs.getString(1), rs.getLong(2));
            return Map.copyOf(metrics);
        }
    }

    private Connection rootConnection() throws SQLException {
        return DriverManager.getConnection(rootJdbcUrl, rootUser, rootPassword);
    }

    private long connectionId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select connection_id()")) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private AtomicFixture atomicFixture(boolean claimTask) {
        BaseRun run = baseRun(false);
        String cellKey = "entity-1:field-0";
        insertCell(run.runId(), run.rowId(), cellKey, "field-0");
        Map<String, Long> reserved = budget(1);
        Map<String, Object> snapshotBudget = new LinkedHashMap<>();
        reserved.forEach(snapshotBudget::put);
        String taskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                run.runId(), "lock-matrix-task-" + run.runId(), "lock-matrix-idem-" + run.runId(),
                1, "DEEP_CELL", "entity-1", "main", 1, 1, List.of(cellKey), snapshotBudget,
                List.of(new ResearchAgentTaskService.TargetCellBinding(cellKey, 0)),
                new ResearchAgentTaskService.TaskExecutionContext("research-fake",
                        Map.of("source_scope", List.of(Map.of(
                                "source_id", "source-0", "source_title", "Source 0",
                                "sample_text", "prefix trusted quote 0 suffix"))),
                        Map.of("query", "lock matrix question")))).taskId();
        outboxService.enqueue(taskId);
        budgetService.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                run.runId(), taskId, "lock-matrix-budget-" + taskId, reserved));
        if (!claimTask) {
            return new AtomicFixture(run.runId(), taskId, cellKey, 0, 0, null,
                    "deep-cell:" + taskId + ":unclaimed");
        }
        ResearchAgentTaskService.ClaimedTask claim = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(taskId, "worker-a", 300));
        return new AtomicFixture(run.runId(), taskId, cellKey, claim.leaseEpoch(), claim.fencingToken(),
                claim.snapshotDigest(), "deep-cell:" + taskId + ":" + claim.leaseEpoch() + ":" + claim.fencingToken());
    }

    private AtomicFixture expiredAtomicFixture() {
        AtomicFixture fixture = atomicFixture(true);
        jdbcTemplate.update("""
                update research_agent_task
                set lease_expires_at = current_timestamp - interval 5 second
                where id = ?
                """, fixture.taskId());
        return fixture;
    }

    private CoordinatorFixture coordinatorFixture() throws Exception {
        BaseRun run = baseRun(true);
        insertCell(run.runId(), run.rowId(), "entity-1:method", "method");
        return new CoordinatorFixture(run.runId());
    }

    private BaseRun baseRun(boolean trustedDatabaseSource) {
        try {
            String workspaceId = Ids.newId();
            String parentTaskId = Ids.newId();
            String runId = Ids.newId();
            String rowId = Ids.newId();
            jdbcTemplate.update("""
                    insert into workspace(id, owner_id, name, status)
                    values (?, 'local-user', ?, 'ACTIVE')
                    """, workspaceId, "lock-matrix-" + runId);
            jdbcTemplate.update("""
                    insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                    values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                    """, parentTaskId, workspaceId, runId);
            List<String> sourceScope = List.of();
            if (trustedDatabaseSource) sourceScope = List.of(insertTrustedSource(workspaceId));
            jdbcTemplate.update("""
                    insert into research_run(
                        id, workspace_id, task_id, question, profile_key, source_scope_json,
                        status, agent_execution_mode
                    ) values (?, ?, ?, 'lock matrix question', 'DEFAULT', ?, 'RUNNING', 'INCREMENTAL_V1')
                    """, runId, workspaceId, parentTaskId, objectMapper.writeValueAsString(sourceScope));
            jdbcTemplate.update("""
                    insert into research_row(id, research_run_id, row_key, row_status)
                    values (?, ?, 'entity-1', 'CANDIDATE_READY')
                    """, rowId, runId);
            return new BaseRun(runId, rowId);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create LockMatrix run fixture", exception);
        }
    }

    private String insertTrustedSource(String workspaceId) {
        String fileId = Ids.newId();
        String sourceId = Ids.newId();
        String snapshotId = Ids.newId();
        String chunkId = Ids.newId();
        jdbcTemplate.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size)
                values (?, ?, ?, ?, 20)
                """, fileId, workspaceId, "lock-matrix/source.txt", "a".repeat(64));
        jdbcTemplate.update("""
                insert into source(
                    id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status
                ) values (?, ?, ?, 'Lock Matrix Source', 'TEXT', 'READY', 'READY', 'READY')
                """, sourceId, workspaceId, fileId);
        jdbcTemplate.update("""
                insert into source_snapshot(
                    id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status
                ) values (?, ?, ?, 1, 'lock-matrix/source.txt', ?, 'READY', 'READY')
                """, snapshotId, sourceId, fileId, "b".repeat(64));
        jdbcTemplate.update("""
                insert into source_chunk(
                    id, workspace_id, source_id, source_snapshot_id, chunk_no, content, token_estimate
                ) values (?, ?, ?, ?, 1, 'The method is documented.', 5)
                """, chunkId, workspaceId, sourceId, snapshotId);
        jdbcTemplate.update("""
                insert into source_window(id, source_chunk_id, window_no, content)
                values (?, ?, 1, 'The method is documented.')
                """, Ids.newId(), chunkId);
        return sourceId;
    }

    private void insertCell(String runId, String rowId, String cellKey, String columnKey) {
        jdbcTemplate.update("""
                insert into research_cell(
                    id, research_run_id, research_row_id, cell_key, column_key, candidate_value,
                    cell_status, repair_count, cell_version, plan_revision, entity_set_version
                ) values (?, ?, ?, ?, ?, 'old', 'CANDIDATE_READY', 0, 0, 1, 1)
                """, Ids.newId(), runId, rowId, cellKey, columnKey);
    }

    private Map<String, Long> budget(int cells) {
        return Map.of(
                "llm_calls", 2L,
                "search_calls", 2L,
                "fetch_calls", 2L,
                "read_calls", 2L,
                "extract_calls", 2L,
                "evidence_cards", (long) cells + 2,
                "evidence_appended", (long) cells + 2,
                "candidates_submitted", (long) cells + 1,
                "candidate_merges_accepted", (long) cells,
                "candidate_merges_rejected", (long) cells);
    }

    private ResearchAgentCompletionEnvelope envelope(AtomicFixture fixture) {
        String suffix = fixture.taskId().substring(0, 8);
        String evidenceKey = "ev-" + suffix;
        ResearchAgentCompletionEnvelope unsigned = new ResearchAgentCompletionEnvelope(
                "research-agent-completion.v1", fixture.taskId(), "worker-a", fixture.leaseEpoch(),
                fixture.fencingToken(), fixture.executionKey(), fixture.snapshotDigest(),
                "CANDIDATES_PROPOSED",
                Map.of("llm_calls", 0L, "search_calls", 1L, "fetch_calls", 1L,
                        "read_calls", 1L, "extract_calls", 1L, "evidence_cards", 1L,
                        "candidates_submitted", 1L),
                Map.of("search_hits", 1L, "documents", 1L, "windows", 1L),
                "sha256:" + "3".repeat(64),
                List.of(new ResearchAgentCompletionEnvelope.Evidence(
                        evidenceKey, "window-0", "source-0", "Source 0", "lock matrix question",
                        "field-0", "trusted quote 0", "value-0", "SUPPORTS", 900_000, 0, "WORKSPACE")),
                List.of(new ResearchAgentCompletionEnvelope.Candidate(
                        "cand-" + suffix, fixture.cellKey(), 0, "value-0", List.of(evidenceKey), 900_000)),
                null);
        return unsigned.withEnvelopeDigest(canonicalizer.digest(unsigned));
    }

    private ResearchAgentCompletionEnvelope conflicting(ResearchAgentCompletionEnvelope source) {
        ResearchAgentCompletionEnvelope changed = new ResearchAgentCompletionEnvelope(
                source.schemaVersion(), source.taskId(), source.workerInstanceId(), source.leaseEpoch(),
                source.fencingToken(), source.executionKey(), source.taskSnapshotDigest(), source.terminationReason(),
                source.budgetUsage(), source.telemetry(), "sha256:" + "8".repeat(64),
                source.evidence(), source.candidates(), null);
        return changed.withEnvelopeDigest(canonicalizer.digest(changed));
    }

    private void assertRunInvariants(String runId) throws Exception {
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from (
                    select research_agent_task_id from research_agent_completion c
                    join research_agent_task t on t.id = c.research_agent_task_id
                    where t.research_run_id = ?
                    group by research_agent_task_id having count(*) > 1
                ) duplicate_completion
                """, Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from research_agent_execution e
                join research_agent_task t on t.id = e.research_agent_task_id
                left join research_agent_completion c on c.execution_id = e.id
                where t.research_run_id = ? and c.id is null
                """, Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_cell
                where research_run_id = ? and (cell_version < 0 or cell_version > 1)
                """, Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from research_cell_evidence rce
                left join research_agent_completion c on c.id = rce.agent_completion_id
                left join source_evidence se on se.id = rce.source_evidence_id
                where rce.research_run_id = ?
                  and (c.id is null or se.agent_completion_id <> rce.agent_completion_id)
                """, Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task
                where research_run_id = ? and updated_at is null
                """, Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_cell
                where research_run_id = ? and updated_at is null
                """, Integer.class, runId)).isZero();
        assertRunReservations(runId);
    }

    private void assertRunReservations(String runId) throws Exception {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select state, reserved_json, consumed_json, released_json,
                       agent_completion_id, settlement_key, updated_at
                from research_budget_reservation
                where research_run_id = ? order by id
                """, runId);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("updated_at")).isNotNull();
            Map<String, Long> reserved = readLongMap(row.get("reserved_json"));
            Map<String, Long> consumed = readLongMap(row.get("consumed_json"));
            Map<String, Long> released = readLongMap(row.get("released_json"));
            assertThat(reserved.keySet()).containsExactlyInAnyOrderElementsOf(BUDGET_KEYS);
            assertThat(consumed.keySet()).containsExactlyInAnyOrderElementsOf(BUDGET_KEYS);
            assertThat(released.keySet()).containsExactlyInAnyOrderElementsOf(BUDGET_KEYS);
            String state = String.valueOf(row.get("state"));
            for (String key : BUDGET_KEYS) {
                if ("RESERVED".equals(state)) {
                    assertThat(consumed.get(key)).isZero();
                    assertThat(released.get(key)).isZero();
                } else {
                    assertThat(consumed.get(key) + released.get(key)).isEqualTo(reserved.get(key));
                }
            }
            if ("SETTLED".equals(state)) {
                assertThat(row.get("agent_completion_id")).isNotNull();
                assertThat(row.get("settlement_key")).isNotNull();
            }
            if ("RELEASED".equals(state) || "RESERVED".equals(state)) {
                assertThat(row.get("agent_completion_id")).isNull();
            }
        }
    }

    private Map<String, Long> readLongMap(Object raw) throws Exception {
        return objectMapper.readValue(String.valueOf(raw), new TypeReference<Map<String, Long>>() { });
    }

    private void assertCompleteState(AtomicFixture fixture) throws Exception {
        assertThat(taskStatus(fixture.taskId())).isEqualTo("SUBMITTED");
        assertThat(reservationState(fixture.taskId())).isEqualTo("SETTLED");
        assertThat(completionCount(fixture.taskId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_execution where research_agent_task_id = ?",
                Integer.class, fixture.taskId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from source_evidence where research_run_id = ? and agent_completion_id is not null",
                Integer.class, fixture.runId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_candidate where research_run_id = ? and agent_completion_id is not null",
                Integer.class, fixture.runId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_cell_merge where research_run_id = ? and agent_completion_id is not null",
                Integer.class, fixture.runId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_cell_evidence where research_run_id = ? and agent_completion_id is not null",
                Integer.class, fixture.runId())).isEqualTo(1);
        Map<String, Object> cell = jdbcTemplate.queryForMap("""
                select cell_version, cell_status, active_task_id, confidence_score_ppm
                from research_cell where research_run_id = ? and cell_key = ?
                """, fixture.runId(), fixture.cellKey());
        assertThat(cell).containsEntry("cell_version", 1)
                .containsEntry("cell_status", "VERIFIED")
                .containsEntry("active_task_id", null)
                .containsEntry("confidence_score_ppm", 900_000);
        assertRunReservations(fixture.runId());
    }

    private void assertCancelledState(AtomicFixture fixture) throws Exception {
        assertThat(runStatus(fixture.runId())).isEqualTo("CANCELLED");
        assertThat(taskStatus(fixture.taskId())).isEqualTo("CANCELLED");
        assertThat(reservationState(fixture.taskId())).isEqualTo("RELEASED");
        assertThat(completionCount(fixture.taskId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select active_task_id from research_cell where research_run_id = ? and cell_key = ?",
                String.class, fixture.runId(), fixture.cellKey())).isNull();
        assertRunReservations(fixture.runId());
    }

    private String runStatus(String runId) {
        return jdbcTemplate.queryForObject("select status from research_run where id = ?", String.class, runId);
    }

    private String taskStatus(String taskId) {
        return jdbcTemplate.queryForObject("select status from research_agent_task where id = ?", String.class, taskId);
    }

    private String reservationState(String taskId) {
        return jdbcTemplate.queryForObject(
                "select state from research_budget_reservation where research_agent_task_id = ?",
                String.class, taskId);
    }

    private int completionCount(String taskId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from research_agent_completion where research_agent_task_id = ?",
                Integer.class, taskId);
    }

    private Map<String, Object> fullState(String runId) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("workspace", normalizedRows("""
                select w.* from workspace w join research_run r on r.workspace_id = w.id
                where r.id = ? order by w.id
                """, runId));
        state.put("parent_task", normalizedRows("""
                select t.* from task t join research_run r on r.task_id = t.id
                where r.id = ? order by t.id
                """, runId));
        state.put("run", normalizedRows("select * from research_run where id = ? order by id", runId));
        state.put("rows", normalizedRows("select * from research_row where research_run_id = ? order by id", runId));
        state.put("tasks", normalizedRows(
                "select * from research_agent_task where research_run_id = ? order by id", runId));
        state.put("executions", normalizedRows("""
                select e.* from research_agent_execution e
                join research_agent_task t on t.id = e.research_agent_task_id
                where t.research_run_id = ? order by e.id
                """, runId));
        state.put("completions", normalizedRows("""
                select c.* from research_agent_completion c
                join research_agent_task t on t.id = c.research_agent_task_id
                where t.research_run_id = ? order by c.id
                """, runId));
        state.put("evidence", normalizedRows(
                "select * from source_evidence where research_run_id = ? order by id", runId));
        state.put("candidates", normalizedRows(
                "select * from research_agent_candidate where research_run_id = ? order by id", runId));
        state.put("merges", normalizedRows(
                "select * from research_cell_merge where research_run_id = ? order by id", runId));
        state.put("cell_evidence", normalizedRows(
                "select * from research_cell_evidence where research_run_id = ? order by id", runId));
        state.put("cells", normalizedRows(
                "select * from research_cell where research_run_id = ? order by id", runId));
        state.put("budgets", normalizedRows(
                "select * from research_budget_reservation where research_run_id = ? order by id", runId));
        state.put("outbox", normalizedRows(
                "select * from research_agent_outbox where research_run_id = ? order by id", runId));
        state.put("sha256", digestState(state));
        return Map.copyOf(state);
    }

    private Map<String, Object> fullStateSafely(String runId) {
        try {
            return fullState(runId);
        } catch (RuntimeException failure) {
            return Map.of("capture_failure", failure.toString());
        }
    }

    private List<Map<String, String>> normalizedRows(String sql, Object... arguments) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, arguments);
        List<Map<String, String>> normalized = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, String> value = new TreeMap<>();
            row.forEach((key, item) -> value.put(key, normalizeValue(item)));
            normalized.add(Collections.unmodifiableMap(value));
        }
        return List.copyOf(normalized);
    }

    private String normalizeValue(Object value) {
        if (value == null) return "<NULL>";
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        return String.valueOf(value);
    }

    private String digestState(Map<String, Object> state) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(state);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            StringBuilder result = new StringBuilder("sha256:");
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot digest LockMatrix state", exception);
        }
    }

    private void assertNoDeadlockOrTimeout(Outcome outcome) {
        if (outcome.error() == null) return;
        String text = throwableText(outcome.error()).toLowerCase(java.util.Locale.ROOT);
        assertThat(text).doesNotContain("deadlock", "lock wait timeout", "sqlstate=40001", "error 1213", "error 1205");
    }

    private String throwableText(Throwable throwable) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            text.append(current.getClass().getName()).append(':').append(current.getMessage()).append('|');
        }
        return text.toString();
    }

    private <T> T success(Outcome outcome, Class<T> type) {
        assertThat(outcome.error()).isNull();
        assertThat(outcome.value()).isInstanceOf(type);
        return type.cast(outcome.value());
    }

    private void assertBusiness(Outcome outcome, String code) {
        assertThat(outcome.value()).isNull();
        assertThat(outcome.error()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) outcome.error()).code()).isEqualTo(code);
    }

    private Outcome capture(Callable<Object> operation) {
        long started = System.nanoTime();
        try {
            return new Outcome(operation.call(), null,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (Throwable failure) {
            return new Outcome(null, failure,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    private Map<String, Object> outcomeEvidence(Outcome outcome) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("duration_ms", outcome.durationMillis());
        if (outcome.error() == null) {
            value.put("kind", "SUCCESS");
            value.put("type", outcome.value() == null ? "null" : outcome.value().getClass().getName());
            value.put("value", String.valueOf(outcome.value()));
        } else {
            value.put("kind", "ERROR");
            value.put("type", outcome.error().getClass().getName());
            value.put("message", String.valueOf(outcome.error().getMessage()));
            if (outcome.error() instanceof BusinessException business) value.put("business_code", business.code());
        }
        return Map.copyOf(value);
    }

    private void markVerified(String caseId) throws IOException {
        Map<String, Object> evidence = evidenceByCase.get(caseId);
        assertThat(evidence).isNotNull();
        assertThat(evidence.get("status")).isEqualTo("WAIT_GRAPH_VERIFIED");
        evidence.put("status", "VERIFIED");
        writeCaseEvidence(caseId);
    }

    private void writeCaseEvidence(String caseId) throws IOException {
        writeJson("case-" + caseId.toLowerCase(java.util.Locale.ROOT) + ".json", evidenceByCase.get(caseId));
    }

    private void writeJson(String fileName, Object value) throws IOException {
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        Files.write(evidenceDirectory.resolve(fileName), json);
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private String redactedJdbcUrl(String value) {
        int query = value.indexOf('?');
        return query < 0 ? value : value.substring(0, query);
    }

    private record BaseRun(String runId, String rowId) { }

    private record AtomicFixture(
            String runId,
            String taskId,
            String cellKey,
            int leaseEpoch,
            long fencingToken,
            String snapshotDigest,
            String executionKey
    ) { }

    private record CoordinatorFixture(String runId) { }

    private record Outcome(Object value, Throwable error, long durationMillis) { }

    private record RaceResult(Outcome leader, Outcome follower) { }

    private record WaitEdge(
            long requestingThreadId,
            long requestingProcessId,
            String requestingUser,
            long blockingThreadId,
            long blockingProcessId,
            String blockingUser,
            String objectSchema,
            String objectName,
            String indexName,
            String lockType,
            String lockMode,
            String lockStatus,
            String lockData
    ) { }
}
