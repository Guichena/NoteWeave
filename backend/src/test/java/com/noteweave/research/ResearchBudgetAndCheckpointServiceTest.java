package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ResearchBudgetAndCheckpointServiceTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchAgentTaskService taskService;
    @Autowired private ResearchBudgetAndCheckpointService service;
    @Autowired private MockMvc mockMvc;

    private String workspaceId;
    private String runId;
    private String agentTaskId;

    @BeforeEach
    void setUp() {
        workspaceId = Ids.newId();
        String parentTaskId = Ids.newId();
        runId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')", workspaceId, "budget-test");
        jdbcTemplate.update("insert into task(id, workspace_id, task_type, task_status, target_type, target_id) values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)", parentTaskId, workspaceId, runId);
        jdbcTemplate.update("insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json, status) values (?, ?, ?, 'q', 'DEFAULT', '[]', 'RUNNING')", runId, workspaceId, parentTaskId);
        agentTaskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                runId, "budget-task", "budget-task-idem", 1, "DEEP_CELL", "entity-1", "branch-main", 0, 1,
                List.of("entity-1:method"), Map.of("llm_calls", 3)
        )).taskId();
    }

    @Test
    void shouldReserveSettleAndReleaseBudgetWithDimensionalConservation() {
        ResearchBudgetAndCheckpointService.ReservationReceipt reserved = service.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                runId, agentTaskId, "reserve-1", Map.of("llm_calls", 3L, "input_tokens", 100L)
        ));
        ResearchBudgetAndCheckpointService.ReservationReceipt replay = service.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                runId, agentTaskId, "reserve-1", Map.of("llm_calls", 3L, "input_tokens", 100L)
        ));
        service.settle(new ResearchBudgetAndCheckpointService.SettleCommand(reserved.reservationId(), Map.of("llm_calls", 2L, "input_tokens", 40L)));
        ResearchBudgetAndCheckpointService.BudgetSnapshot snapshot = service.release(reserved.reservationId());

        assertThat(reserved.reservationId()).isEqualTo(replay.reservationId());
        assertThat(snapshot.reserved()).isEqualTo(Map.of("llm_calls", 3L, "input_tokens", 100L));
        assertThat(snapshot.consumed()).isEqualTo(Map.of("llm_calls", 2L, "input_tokens", 40L));
        assertThat(snapshot.released()).isEqualTo(Map.of("llm_calls", 1L, "input_tokens", 60L));
        assertThat(snapshot.state()).isEqualTo("SETTLED");
        assertThat(jdbcTemplate.queryForObject(
                "select released_json from research_budget_reservation where id = ?",
                String.class, reserved.reservationId())).contains("\"llm_calls\":1", "\"input_tokens\":60");
        assertThat(jdbcTemplate.queryForObject(
                "select state from research_budget_reservation where id = ?",
                String.class, reserved.reservationId())).isEqualTo("SETTLED");
        assertThatThrownBy(() -> service.settle(new ResearchBudgetAndCheckpointService.SettleCommand(
                reserved.reservationId(), Map.of("llm_calls", 4L)
        ))).isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldAllocateMonotonicCheckpointSequenceAndRejectRegressingHighWaterMarks() {
        ResearchBudgetAndCheckpointService.CheckpointReceipt first = service.appendCheckpoint(checkpoint(1, 2, 3));
        ResearchBudgetAndCheckpointService.CheckpointReceipt second = service.appendCheckpoint(checkpoint(2, 4, 6));

        assertThat(first.checkpointSeq()).isEqualTo(1);
        assertThat(second.checkpointSeq()).isEqualTo(2);
        assertThatThrownBy(() -> service.appendCheckpoint(checkpoint(1, 3, 7)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_CHECKPOINT_HIGH_WATER_REGRESSION");
    }

    @ParameterizedTest
    @ValueSource(strings = {"service_settle", "service_release", "controller_settle", "controller_release"})
    void shouldRejectEveryDirectAtomicReservationMutationBeforeAnyDatabaseChange(String operation) throws Exception {
        String reservationId = atomicReservation(operation);
        Map<String, Object> before = reservationState(reservationId);

        switch (operation) {
            case "service_settle" -> assertAtomicRequired(() -> service.settle(
                    new ResearchBudgetAndCheckpointService.SettleCommand(
                            reservationId, Map.of("llm_calls", 0L))));
            case "service_release" -> assertAtomicRequired(() -> service.release(reservationId));
            case "controller_settle" -> mockMvc.perform(post(
                            "/internal/research-agent/budget-reservations/{reservationId}/settle", reservationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"values\":{\"llm_calls\":0}}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED"));
            case "controller_release" -> mockMvc.perform(post(
                            "/internal/research-agent/budget-reservations/{reservationId}/release", reservationId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED"));
            default -> throw new AssertionError("unknown operation: " + operation);
        }

        assertThat(reservationState(reservationId)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEEP_CELL", "COUNTERFACTUAL"})
    void shouldRejectDirectSettlementForEveryV2AtomicRole(String role) {
        String reservationId = atomicReservation("v2-" + role.toLowerCase());
        String taskId = jdbcTemplate.queryForObject(
                "select research_agent_task_id from research_budget_reservation where id = ?",
                String.class, reservationId);
        jdbcTemplate.update("""
                update research_agent_task
                set role = ?, snapshot_schema_version = 'research-agent-task-snapshot.v2',
                    logical_task_key = 'atomic:v2:budget', candidate_quorum = 1, candidate_slot = 1
                where id = ?
                """, role, taskId);
        Map<String, Object> before = reservationState(reservationId);

        assertAtomicRequired(() -> service.settle(
                new ResearchBudgetAndCheckpointService.SettleCommand(
                        reservationId, Map.of("llm_calls", 0L))));

        assertThat(reservationState(reservationId)).isEqualTo(before);
    }

    private ResearchBudgetAndCheckpointService.CheckpointCommand checkpoint(long taskHighWater, long candidateHighWater, long mergeHighWater) {
        return new ResearchBudgetAndCheckpointService.CheckpointCommand(
                runId, 1, 1, 0, 1, "ledger-hash", taskHighWater, candidateHighWater, mergeHighWater,
                Map.of("reserved", Map.of("llm_calls", 3)), Map.of("reason", "test")
        );
    }

    private String atomicReservation(String suffix) {
        String atomicWorkspace = Ids.newId();
        String parentTask = Ids.newId();
        String atomicRun = Ids.newId();
        String rowId = Ids.newId();
        String cellKey = "entity-atomic:method-" + suffix;
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')",
                atomicWorkspace, "atomic-budget-" + suffix);
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, parentTask, atomicWorkspace, atomicRun);
        jdbcTemplate.update("""
                insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json,
                    status, agent_execution_mode)
                values (?, ?, ?, 'q', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')
                """, atomicRun, atomicWorkspace, parentTask);
        jdbcTemplate.update("insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-atomic', 'CANDIDATE_READY')",
                rowId, atomicRun);
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key,
                    candidate_value, cell_status, repair_count, cell_version, plan_revision, entity_set_version)
                values (?, ?, ?, ?, 'method', 'old', 'CANDIDATE_READY', 0, 0, 1, 1)
                """, Ids.newId(), atomicRun, rowId, cellKey);
        Map<String, Long> budget = atomicBudget();
        Map<String, Object> snapshotBudget = new java.util.LinkedHashMap<>();
        budget.forEach(snapshotBudget::put);
        String taskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                atomicRun, "atomic-budget-task-" + suffix, "atomic-budget-idem-" + suffix, 1,
                "DEEP_CELL", "entity-atomic", "main", 1, 1, List.of(cellKey), snapshotBudget,
                List.of(new ResearchAgentTaskService.TargetCellBinding(cellKey, 0)),
                new ResearchAgentTaskService.TaskExecutionContext(
                        "research-fake",
                        Map.of("source_scope", List.of(Map.of(
                                "source_id", "source-1", "sample_text", "trusted quote"))),
                        Map.of("query", "q")))).taskId();
        return service.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                atomicRun, taskId, "atomic-reserve-" + suffix, budget)).reservationId();
    }

    private Map<String, Long> atomicBudget() {
        return Map.of(
                "llm_calls", 1L, "search_calls", 1L, "fetch_calls", 1L, "read_calls", 1L,
                "extract_calls", 1L, "evidence_cards", 1L, "candidates_submitted", 1L,
                "evidence_appended", 1L, "candidate_merges_accepted", 1L,
                "candidate_merges_rejected", 1L);
    }

    private Map<String, Object> reservationState(String reservationId) {
        return jdbcTemplate.queryForMap("""
                select state, reserved_json, consumed_json, released_json, settled_at, updated_at,
                       agent_completion_id, settlement_key, finalized_at
                from research_budget_reservation where id = ?
                """, reservationId);
    }

    private void assertAtomicRequired(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED");
    }
}
