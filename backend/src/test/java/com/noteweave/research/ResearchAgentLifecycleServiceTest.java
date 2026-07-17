package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ResearchAgentLifecycleServiceTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchAgentTaskService taskService;
    @Autowired private ResearchAgentLifecycleService lifecycleService;
    @Autowired private ResearchAgentCommandOutboxService outboxService;
    @Autowired private ResearchBudgetAndCheckpointService budgetService;

    private String workspaceId;
    private String runId;

    @BeforeEach
    void setUp() {
        workspaceId = Ids.newId();
        String taskId = Ids.newId();
        runId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')", workspaceId, "agent-lifecycle-test");
        jdbcTemplate.update("insert into task(id, workspace_id, task_type, task_status, target_type, target_id) values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)", taskId, workspaceId, runId);
        jdbcTemplate.update("insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json, status, agent_execution_mode) values (?, ?, ?, 'test', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')", runId, workspaceId, taskId);
    }

    @Test
    void shouldMoveExpiredLeaseToRetryWaitAndEnqueueNewDelivery() {
        String taskId = createAndClaim("retry-task");
        jdbcTemplate.update("""
                update research_agent_task
                set max_attempts = 2, lease_expires_at = timestampadd(second, -1, current_timestamp)
                where id = ?
                """, taskId);

        ResearchAgentLifecycleService.ReapReceipt receipt = lifecycleService.reapExpiredLeases();

        assertThat(receipt.retryWaitingCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select status from research_agent_task where id = ?", String.class, taskId)).isEqualTo("RETRY_WAIT");
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_outbox where research_agent_task_id = ?", Integer.class, taskId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task
                where id = ? and next_attempt_at > current_timestamp
                """, Integer.class, taskId)).isEqualTo(1);
    }

    @Test
    void shouldFailExpiredLeaseAfterAttemptLimitAndRejectOldFence() {
        String taskId = createAndClaim("exhausted-task");
        ResearchAgentTaskService.ClaimedTask claim = taskService.claimTask(new ResearchAgentTaskService.ClaimCommand(taskId, "worker-a", 30));
        ResearchBudgetAndCheckpointService budget = new ResearchBudgetAndCheckpointService(jdbcTemplate, new com.fasterxml.jackson.databind.ObjectMapper());
        String reservationId = budget.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                runId, taskId, "reserve-exhausted", Map.of("llm_calls", 2L)
        )).reservationId();
        jdbcTemplate.update("""
                update research_agent_task
                set max_attempts = 1, lease_expires_at = timestampadd(second, -1, current_timestamp)
                where id = ?
                """, taskId);

        lifecycleService.reapExpiredLeases();

        assertThat(jdbcTemplate.queryForObject("select status from research_agent_task where id = ?", String.class, taskId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("select state from research_budget_reservation where id = ?", String.class, reservationId)).isEqualTo("RELEASED");
        assertThatThrownBy(() -> taskService.submitExecution(new ResearchAgentTaskService.SubmitCommand(
                taskId, "worker-a", claim.leaseEpoch(), claim.fencingToken(), "stale", "DONE", Map.of("llm_calls", 0), ""
        ))).isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldCancelNonTerminalTasksAndReleaseReservations() {
        String taskId = createAndClaim("cancel-task");
        ResearchAgentTaskService.ClaimedTask claim = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(taskId, "worker-a", 30)
        );
        ResearchBudgetAndCheckpointService budget = new ResearchBudgetAndCheckpointService(jdbcTemplate, new com.fasterxml.jackson.databind.ObjectMapper());
        String reservationId = budget.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(runId, taskId, "reserve-cancel", Map.of("llm_calls", 2L))).reservationId();

        ResearchAgentLifecycleService.CancelReceipt receipt = lifecycleService.cancelRun(runId, "USER_CANCELLED");

        assertThat(receipt.cancelledTaskCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select status from research_run where id = ?", String.class, runId)).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("select status from research_agent_task where id = ?", String.class, taskId)).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("select state from research_budget_reservation where id = ?", String.class, reservationId)).isEqualTo("RELEASED");
        assertThatThrownBy(() -> taskService.submitExecution(new ResearchAgentTaskService.SubmitCommand(
                taskId, "worker-a", claim.leaseEpoch(), claim.fencingToken(), "after-cancel", "DONE", Map.of("llm_calls", 0), ""
                ))).isInstanceOf(BusinessException.class);
    }

    /** Atomic lifecycle is the only authority allowed to release this reservation. */
    @Test
    void shouldCancelAtomicTaskAndReleaseItsTenDimensionReservationThroughLifecycleAuthority() {
        AtomicTask atomic = createAtomicAndClaim("atomic-cancel");

        ResearchAgentLifecycleService.CancelReceipt receipt = lifecycleService.cancelRun(runId, "USER_CANCELLED");

        assertThat(receipt.cancelledTaskCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_run where id = ?", String.class, runId)).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?", String.class, atomic.taskId()))
                .isEqualTo("CANCELLED");
        assertReleasedConservation(atomic.reservationId());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_completion where research_agent_task_id = ?",
                Integer.class, atomic.taskId())).isZero();
    }

    @Test
    void shouldFailRetryExhaustedAtomicTaskAndReleaseItsTenDimensionReservationThroughLifecycleAuthority() {
        AtomicTask atomic = createAtomicAndClaim("atomic-exhausted");
        jdbcTemplate.update("""
                update research_agent_task
                set max_attempts = 1, lease_expires_at = timestampadd(second, -1, current_timestamp)
                where id = ?
                """, atomic.taskId());

        ResearchAgentLifecycleService.ReapReceipt receipt = lifecycleService.reapExpiredLeases();

        assertThat(receipt.failedCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("""
                select status, terminal_reason from research_agent_task where id = ?
                """, atomic.taskId()))
                .containsEntry("status", "FAILED")
                .containsEntry("terminal_reason", "LEASE_RETRY_EXHAUSTED");
        assertReleasedConservation(atomic.reservationId());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_completion where research_agent_task_id = ?",
                Integer.class, atomic.taskId())).isZero();
    }

    @Test
    void shouldReleaseQuorumCellBindingOnlyAfterEveryCandidateSlotIsTerminal() {
        QuorumTasks quorum = createQuorumAndClaim("quorum-exhausted");
        jdbcTemplate.update("""
                update research_agent_task
                set max_attempts = 1, lease_expires_at = timestampadd(second, -1, current_timestamp)
                where id = ?
                """, quorum.first().taskId());

        ResearchAgentLifecycleService.ReapReceipt firstReap = lifecycleService.reapExpiredLeases();

        assertThat(firstReap.failedCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select active_task_id from research_cell where research_run_id = ? and cell_key = ?",
                String.class, runId, quorum.cellKey())).isEqualTo(quorum.groupKey());
        assertReleasedConservation(quorum.first().reservationId());
        assertThat(jdbcTemplate.queryForObject(
                "select state from research_budget_reservation where id = ?",
                String.class, quorum.second().reservationId())).isEqualTo("RESERVED");

        jdbcTemplate.update("""
                update research_agent_task
                set max_attempts = 1, lease_expires_at = timestampadd(second, -1, current_timestamp)
                where id = ?
                """, quorum.second().taskId());
        ResearchAgentLifecycleService.ReapReceipt secondReap = lifecycleService.reapExpiredLeases();

        assertThat(secondReap.failedCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select active_task_id is null from research_cell where research_run_id = ? and cell_key = ?",
                Boolean.class, runId, quorum.cellKey())).isTrue();
        assertReleasedConservation(quorum.second().reservationId());
    }

    @Test
    void shouldAppendDeliveryFailureIdempotentlyAndRedriveThroughOutbox() {
        String taskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                runId, "dlq-task", "idem-dlq", 1, "DEEP_CELL", "entity-1", "main", 1, 1,
                List.of("entity-1:field"), Map.of("llm_calls", 2)
        )).taskId();
        ResearchAgentLifecycleService.DeliveryFailureCommand command = new ResearchAgentLifecycleService.DeliveryFailureCommand(
                runId, taskId, "", "dlq:message-1", "COMMAND_SCHEMA_INVALID", "sha256:failure", 1
        );

        ResearchAgentLifecycleService.DeliveryFailureReceipt first = lifecycleService.recordDeliveryFailure(command);
        ResearchAgentLifecycleService.DeliveryFailureReceipt replay = lifecycleService.recordDeliveryFailure(command);
        ResearchAgentLifecycleService.RedriveReceipt redrive = lifecycleService.redriveDeliveryFailure(first.failureId());
        ResearchAgentLifecycleService.RedriveReceipt redriveReplay = lifecycleService.redriveDeliveryFailure(first.failureId());

        assertThat(first.failureId()).isEqualTo(replay.failureId());
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(redrive.idempotentReplay()).isFalse();
        assertThat(redriveReplay.idempotentReplay()).isTrue();
        assertThat(jdbcTemplate.queryForObject("select redrive_status from research_agent_delivery_failure where id = ?", String.class, first.failureId())).isEqualTo("REDRIVEN");
        assertThat(jdbcTemplate.queryForObject("select status from research_agent_outbox where research_agent_task_id = ?", String.class, taskId)).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject("select delivery_no from research_agent_outbox where research_agent_task_id = ?", Integer.class, taskId)).isEqualTo(1);
    }

    @Test
    void shouldBindDeliveryFailureToMatchingOutboxAndDeliveryAttempt() {
        String taskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                runId, "dlq-bound-task", "idem-dlq-bound", 1, "DEEP_CELL", "entity-1", "main", 1, 1,
                List.of("entity-1:field"), Map.of("llm_calls", 2))).taskId();
        String outboxId = outboxService.enqueue(taskId).outboxId();

        var receipt = lifecycleService.recordDeliveryFailure(new ResearchAgentLifecycleService.DeliveryFailureCommand(
                runId, taskId, outboxId, outboxId + ":delivery:1:TimeoutError",
                "TimeoutError", "sha256:failure", 1));

        assertThat(jdbcTemplate.queryForObject(
                "select research_agent_outbox_id from research_agent_delivery_failure where id = ?",
                String.class, receipt.failureId())).isEqualTo(outboxId);
        assertThatThrownBy(() -> lifecycleService.recordDeliveryFailure(
                new ResearchAgentLifecycleService.DeliveryFailureCommand(
                        runId, taskId, outboxId, outboxId + ":delivery:2:TimeoutError",
                        "TimeoutError", "sha256:failure-2", 2)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_DELIVERY_FAILURE_OUTBOX_INVALID");
    }

    private String createAndClaim(String taskKey) {
        String taskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                runId, taskKey, "idem-" + taskKey, 1, "DEEP_CELL", "entity-1", "main", 1, 1,
                List.of("entity-1:field"), Map.of("llm_calls", 2)
        )).taskId();
        taskService.claimTask(new ResearchAgentTaskService.ClaimCommand(taskId, "worker-a", 30));
        return taskId;
    }

    private AtomicTask createAtomicAndClaim(String taskKey) {
        String rowId = Ids.newId();
        String cellKey = "entity-1:" + taskKey;
        jdbcTemplate.update("""
                insert into research_row(id, research_run_id, row_key, row_status)
                values (?, ?, ?, 'CANDIDATE_READY')
                """, rowId, runId, "row-" + taskKey);
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key,
                    candidate_value, cell_status, repair_count, cell_version, plan_revision, entity_set_version)
                values (?, ?, ?, ?, 'method', 'old', 'CANDIDATE_READY', 0, 0, 1, 1)
                """, Ids.newId(), runId, rowId, cellKey);
        Map<String, Long> budget = atomicBudget();
        Map<String, Object> snapshotBudget = new java.util.LinkedHashMap<>();
        budget.forEach(snapshotBudget::put);
        String taskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                runId, taskKey, "idem-" + taskKey, 1, "DEEP_CELL", "entity-1", "main", 1, 1,
                List.of(cellKey), snapshotBudget,
                List.of(new ResearchAgentTaskService.TargetCellBinding(cellKey, 0)),
                new ResearchAgentTaskService.TaskExecutionContext(
                        "research-fake",
                        Map.of("source_scope", List.of(Map.of(
                                "source_id", "source-1", "sample_text", "trusted quote"))),
                        Map.of("query", "q")))).taskId();
        String reservationId = budgetService.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                runId, taskId, "reserve-" + taskKey, budget)).reservationId();
        ResearchAgentTaskService.ClaimedTask claim = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(taskId, "worker-a", 30));
        return new AtomicTask(taskId, reservationId, claim.leaseEpoch(), claim.fencingToken());
    }

    private QuorumTasks createQuorumAndClaim(String taskKey) {
        String rowId = Ids.newId();
        String cellKey = "entity-1:" + taskKey;
        String groupKey = "quorum:" + Ids.newId();
        jdbcTemplate.update("""
                insert into research_row(id, research_run_id, row_key, row_status)
                values (?, ?, ?, 'CANDIDATE_READY')
                """, rowId, runId, "row-" + taskKey);
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key,
                    candidate_value, cell_status, repair_count, cell_version, plan_revision, entity_set_version, high_risk)
                values (?, ?, ?, ?, 'method', 'old', 'CANDIDATE_READY', 0, 0, 1, 1, true)
                """, Ids.newId(), runId, rowId, cellKey);

        AtomicTask first = createQuorumSlot(taskKey, cellKey, groupKey, 1, "DEEP_CELL");
        AtomicTask second = createQuorumSlot(taskKey, cellKey, groupKey, 2, "COUNTERFACTUAL");
        return new QuorumTasks(cellKey, groupKey, first, second);
    }

    private AtomicTask createQuorumSlot(String taskKey, String cellKey, String groupKey, int slot, String role) {
        Map<String, Long> budget = atomicBudget();
        Map<String, Object> snapshotBudget = new java.util.LinkedHashMap<>();
        budget.forEach(snapshotBudget::put);
        String taskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                runId, taskKey + "-slot-" + slot, "idem-" + taskKey + "-slot-" + slot, 1,
                role, "entity-1", "main", 1, 1, List.of(cellKey), snapshotBudget,
                List.of(new ResearchAgentTaskService.TargetCellBinding(cellKey, 0)),
                new ResearchAgentTaskService.TaskExecutionContext(
                        "research-fake", Map.of("source_scope", List.of()), Map.of("query", "q")))).taskId();
        jdbcTemplate.update("""
                update research_agent_task
                set logical_task_key = ?, quorum_group_key = ?, candidate_quorum = 2, candidate_slot = ?
                where id = ?
                """, taskKey, groupKey, slot, taskId);
        String reservationId = budgetService.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                runId, taskId, "reserve-" + taskKey + "-slot-" + slot, budget)).reservationId();
        ResearchAgentTaskService.ClaimedTask claim = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(taskId, "worker-" + slot, 300));
        return new AtomicTask(taskId, reservationId, claim.leaseEpoch(), claim.fencingToken());
    }

    private Map<String, Long> atomicBudget() {
        return Map.of(
                "llm_calls", 2L, "search_calls", 2L, "fetch_calls", 2L, "read_calls", 2L,
                "extract_calls", 2L, "evidence_cards", 2L, "candidates_submitted", 2L,
                "evidence_appended", 2L, "candidate_merges_accepted", 2L,
                "candidate_merges_rejected", 2L);
    }

    private void assertReleasedConservation(String reservationId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select state, reserved_json, consumed_json, released_json, agent_completion_id, settlement_key
                from research_budget_reservation where id = ?
                """, reservationId);
        assertThat(row).containsEntry("state", "RELEASED")
                .containsEntry("agent_completion_id", null)
                .containsEntry("settlement_key", null);
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Long> reserved = mapper.readValue(String.valueOf(row.get("reserved_json")),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Long>>() { });
            Map<String, Long> consumed = mapper.readValue(String.valueOf(row.get("consumed_json")),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Long>>() { });
            Map<String, Long> released = mapper.readValue(String.valueOf(row.get("released_json")),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Long>>() { });
            assertThat(reserved).hasSize(10);
            assertThat(consumed).hasSize(10);
            assertThat(released).hasSize(10);
            reserved.forEach((dimension, value) -> {
                assertThat(consumed.get(dimension)).isZero();
                assertThat(consumed.get(dimension) + released.get(dimension)).isEqualTo(value);
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }

    private record AtomicTask(String taskId, String reservationId, int leaseEpoch, long fencingToken) { }
    private record QuorumTasks(String cellKey, String groupKey, AtomicTask first, AtomicTask second) { }
}
