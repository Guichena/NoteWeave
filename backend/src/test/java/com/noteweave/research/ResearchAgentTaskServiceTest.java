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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ResearchAgentTaskServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ResearchAgentTaskService taskService;

    @Autowired
    private ResearchAgentExecutionModeService executionModeService;

    @Autowired
    private MockMvc mockMvc;

    private String workspaceId;
    private String runId;

    @BeforeEach
    void setUp() {
        workspaceId = Ids.newId();
        String taskId = Ids.newId();
        runId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')",
                workspaceId, "agent-task-test");
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, taskId, workspaceId, runId);
        jdbcTemplate.update("""
                insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json, status, agent_execution_mode)
                values (?, ?, ?, 'test question', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')
                """, runId, workspaceId, taskId);
    }

    @Test
    void shouldCreateIdempotentlyClaimExpireAndRejectOldLease() {
        ResearchAgentTaskService.TaskSnapshot created = taskService.createTask(command("task-key-1", "idem-1"));
        ResearchAgentTaskService.TaskSnapshot replay = taskService.createTask(command("task-key-1", "idem-1"));

        assertThat(created.taskId()).isEqualTo(replay.taskId());
        ResearchAgentTaskService.ClaimedTask first = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(created.taskId(), "worker-a", 30)
        );
        assertThat(first.leaseEpoch()).isEqualTo(1);
        assertThat(first.fencingToken()).isEqualTo(1);
        assertThatThrownBy(() -> taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(created.taskId(), "worker-b", 30)
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_TASK_NOT_CLAIMABLE");

        jdbcTemplate.update("""
                update research_agent_task
                set lease_expires_at = timestampadd(second, -1, current_timestamp)
                where id = ?
                """, created.taskId());
        taskService.expireLeases();
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?", String.class, created.taskId()
        )).isEqualTo("EXPIRED");
        ResearchAgentTaskService.ClaimedTask reclaimed = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(created.taskId(), "worker-b", 30)
        );
        assertThat(reclaimed.leaseEpoch()).isEqualTo(2);
        assertThat(reclaimed.fencingToken()).isEqualTo(2);
        assertThatThrownBy(() -> taskService.heartbeat(new ResearchAgentTaskService.LeaseCommand(
                created.taskId(), "worker-a", first.leaseEpoch(), first.fencingToken(), 30
        ))).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_TASK_STALE_LEASE");
    }

    @Test
    void shouldUseDatabaseClockForClaimHeartbeatReplayAndExpiration() {
        ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(
                command("task-key-db-clock", "idem-db-clock"));
        ResearchAgentTaskService.ClaimedTask claim = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(task.taskId(), "worker-db-clock", 30));

        Integer claimLeaseSeconds = jdbcTemplate.queryForObject("""
                select timestampdiff(second, current_timestamp, lease_expires_at)
                from research_agent_task where id = ?
                """, Integer.class, task.taskId());
        assertThat(claimLeaseSeconds).isBetween(28, 30);

        ResearchAgentTaskService.ClaimedTask heartbeat = taskService.heartbeat(
                new ResearchAgentTaskService.LeaseCommand(
                        task.taskId(), "worker-db-clock", claim.leaseEpoch(), claim.fencingToken(), 45));
        Integer heartbeatLeaseSeconds = jdbcTemplate.queryForObject("""
                select timestampdiff(second, current_timestamp, lease_expires_at)
                from research_agent_task where id = ?
                """, Integer.class, task.taskId());
        assertThat(heartbeatLeaseSeconds).isBetween(43, 45);
        assertThat(heartbeat.leaseExpiresAt()).isAfter(claim.leaseExpiresAt());

        ResearchAgentTaskService.ClaimedTask replay = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(task.taskId(), "worker-db-clock", 300));
        assertThat(replay.leaseExpiresAt()).isEqualTo(heartbeat.leaseExpiresAt());

        jdbcTemplate.update("""
                update research_agent_task
                set lease_expires_at = timestampadd(second, -1, current_timestamp)
                where id = ?
                """, task.taskId());
        assertThatThrownBy(() -> taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(task.taskId(), "worker-db-clock", 300)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_TASK_NOT_CLAIMABLE");
        assertThatThrownBy(() -> taskService.heartbeat(new ResearchAgentTaskService.LeaseCommand(
                task.taskId(), "worker-db-clock", claim.leaseEpoch(), claim.fencingToken(), 45)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_TASK_STALE_LEASE");

        assertThat(taskService.expireLeases()).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?", String.class, task.taskId()))
                .isEqualTo("EXPIRED");
    }

    @Test
    void shouldRejectCallerSuppliedExpireClockAndAcceptOnlyBodylessDatabaseClock() throws Exception {
        ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(
                command("task-key-expire-http-clock", "idem-expire-http-clock"));
        taskService.claimTask(new ResearchAgentTaskService.ClaimCommand(
                task.taskId(), "worker-http-clock", 120));

        mockMvc.perform(post("/internal/research-agent-tasks/expire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"now\":\"2100-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESEARCH_AGENT_LEASE_CLOCK_OVERRIDE_FORBIDDEN"));
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?", String.class, task.taskId()))
                .isEqualTo("CLAIMED");

        mockMvc.perform(post("/internal/research-agent-tasks/expire"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?", String.class, task.taskId()))
                .isEqualTo("CLAIMED");

        jdbcTemplate.update("""
                update research_agent_task
                set lease_expires_at = timestampadd(second, -1, current_timestamp)
                where id = ?
                """, task.taskId());
        mockMvc.perform(post("/internal/research-agent-tasks/expire"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?", String.class, task.taskId()))
                .isEqualTo("EXPIRED");
    }

    @Test
    void shouldReplayClaimForSameWorkerWithoutAdvancingLeaseOrFencing() {
        ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(command("task-key-3", "idem-3"));
        ResearchAgentTaskService.ClaimedTask first = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(task.taskId(), "worker-a", 300)
        );
        assertThat(jdbcTemplate.queryForObject(
                "select worker_instance_id from research_agent_task where id = ?", String.class, task.taskId()
        )).isEqualTo("worker-a");
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?", String.class, task.taskId()
        )).isEqualTo("CLAIMED");

        ResearchAgentTaskService.ClaimedTask replay = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(task.taskId(), "worker-a", 300)
        );

        assertThat(replay.leaseEpoch()).isEqualTo(first.leaseEpoch());
        assertThat(replay.fencingToken()).isEqualTo(first.fencingToken());
        assertThat(replay.leaseExpiresAt()).isEqualTo(first.leaseExpiresAt());
        assertThat(jdbcTemplate.queryForObject(
                "select attempt_count from research_agent_task where id = ?", Integer.class, task.taskId()
        )).isEqualTo(1);
    }

    @Test
    void shouldRejectClaimWhenRunLeavesIncrementalModeOrBecomesTerminal() {
        ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(command("task-key-mode", "idem-mode"));
        jdbcTemplate.update("update research_run set agent_execution_mode = 'SEQUENTIAL_V1' where id = ?", runId);

        assertThatThrownBy(() -> taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(task.taskId(), "worker-a", 30)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_TASK_NOT_CLAIMABLE");

        jdbcTemplate.update("update research_run set agent_execution_mode = 'INCREMENTAL_V1', status = 'FAILED' where id = ?", runId);
        assertThatThrownBy(() -> taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(task.taskId(), "worker-a", 30)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_TASK_NOT_CLAIMABLE");
        assertThat(jdbcTemplate.queryForObject("select status from research_agent_task where id = ?", String.class, task.taskId()))
                .isEqualTo("PENDING");
    }

    @Test
    void shouldRejectClaimAndHeartbeatAfterRunBecomesTerminal() {
        ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(command("task-key-terminal", "idem-terminal"));
        ResearchAgentTaskService.ClaimedTask claim = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(task.taskId(), "worker-a", 30));
        jdbcTemplate.update("update research_run set status = 'FAILED' where id = ?", runId);

        assertThatThrownBy(() -> taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(task.taskId(), "worker-a", 30)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_TASK_NOT_CLAIMABLE");
        assertThatThrownBy(() -> taskService.heartbeat(new ResearchAgentTaskService.LeaseCommand(
                task.taskId(), "worker-a", claim.leaseEpoch(), claim.fencingToken(), 30)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_TASK_STALE_LEASE");
    }

    @Test
    void shouldKeepTheSoleIncrementalModeIdempotentWhileAgentTaskIsActive() {
        taskService.createTask(command("task-key-mode-switch", "idem-mode-switch"));

        executionModeService.setMode(runId, "INCREMENTAL_V1");
        assertThat(jdbcTemplate.queryForObject("select agent_execution_mode from research_run where id = ?", String.class, runId))
                .isEqualTo("INCREMENTAL_V1");
    }

    @Test
    void shouldKeepTheSoleIncrementalModeIdempotentAfterTerminalAgentTaskHistoryExists() {
        ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(command("task-key-mode-history", "idem-mode-history"));
        jdbcTemplate.update("update research_agent_task set status = 'SUBMITTED', terminal_at = current_timestamp where id = ?", task.taskId());

        executionModeService.setMode(runId, "INCREMENTAL_V1");
        assertThat(jdbcTemplate.queryForObject("select agent_execution_mode from research_run where id = ?", String.class, runId))
                .isEqualTo("INCREMENTAL_V1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL_V1", "SEQUENTIAL_V2", "LOCAL_PARALLEL"})
    void shouldRejectEveryLegacyModeAtTheResearchAgentControlBoundary(String legacyMode) {
        jdbcTemplate.update("update research_run set agent_execution_mode = 'SEQUENTIAL_V1' where id = ?", runId);

        assertThatThrownBy(() -> executionModeService.setMode(runId, legacyMode))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_EXECUTION_MODE_INVALID");
        assertThat(jdbcTemplate.queryForObject(
                "select agent_execution_mode from research_run where id = ?", String.class, runId))
                .isEqualTo("SEQUENTIAL_V1");
    }

    @Test
    void shouldReturnCanonicalServerSnapshotOnlyForExplicitVersionedTaskScope() throws Exception {
        ResearchAgentTaskService.TaskSnapshot legacy = taskService.createTask(command("task-key-legacy", "idem-legacy"));
        ResearchAgentTaskService.ClaimedTask legacyClaim = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(legacy.taskId(), "worker-legacy", 30)
        );
        assertThat(legacyClaim.taskSnapshotJson()).isNull();
        assertThat(legacyClaim.snapshotDigest()).isNull();

        ResearchAgentTaskService.CreateTaskCommand versioned = new ResearchAgentTaskService.CreateTaskCommand(
                runId, "task-key-snapshot", "idem-snapshot", 1, "DEEP_CELL", "entity-1", "branch-main", 2, 3,
                List.of("entity-1:method"), Map.of("llm_calls", 2),
                List.of(new ResearchAgentTaskService.TargetCellBinding("entity-1:method", 3)),
                new ResearchAgentTaskService.TaskExecutionContext(
                        "research-default", Map.of("allow_workspace_sources", true), Map.of("query", "test question")
                )
        );
        String rowId = Ids.newId();
        String cellId = Ids.newId();
        jdbcTemplate.update("insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'CANDIDATE_READY')", rowId, runId);
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key, candidate_value,
                    cell_status, repair_count, cell_version, plan_revision, entity_set_version)
                values (?, ?, ?, 'entity-1:method', 'method', 'old', 'CANDIDATE_READY', 0, 3, 2, 3)
                """, cellId, runId, rowId);
        ResearchAgentTaskService.TaskSnapshot created = taskService.createTask(versioned);
        ResearchAgentTaskService.ClaimedTask first = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(created.taskId(), "worker-snapshot", 30)
        );
        ResearchAgentTaskService.ClaimedTask replay = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(created.taskId(), "worker-snapshot", 30)
        );

        assertThat(first.taskSnapshotJson()).contains("\"schema_version\":\"research-agent-task-snapshot.v1\"")
                .contains("\"workspace_id\":\"" + workspaceId + "\"")
                .contains("\"expected_version\":3")
                .contains("\"provider_key\":\"research-default\"");
        assertThat(first.snapshotDigest()).startsWith("sha256:");
        assertThat(replay.taskSnapshotJson()).isEqualTo(first.taskSnapshotJson());
        assertThat(replay.snapshotDigest()).isEqualTo(first.snapshotDigest());
        assertThat(jdbcTemplate.queryForObject("select active_task_id from research_cell where id = ?", String.class, cellId))
                .isEqualTo(created.taskId());
        assertThat(jdbcTemplate.queryForObject("select lease_epoch from research_cell where id = ?", Integer.class, cellId))
                .isEqualTo(first.leaseEpoch());
        assertThat(jdbcTemplate.queryForObject("select fencing_token from research_cell where id = ?", Long.class, cellId))
                .isEqualTo(first.fencingToken());
    }

    @Test
    void quorumCellBindingShouldReleaseOnlyAfterEveryCandidateLeaseExpires() {
        String rowId = Ids.newId();
        String cellId = Ids.newId();
        jdbcTemplate.update(
                "insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'CANDIDATE_READY')",
                rowId, runId);
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key, candidate_value,
                    cell_status, repair_count, cell_version, plan_revision, entity_set_version)
                values (?, ?, ?, 'entity-1:method', 'method', 'old', 'CANDIDATE_READY', 0, 3, 2, 3)
                """, cellId, runId, rowId);
        ResearchAgentTaskService.CreateTaskCommand firstCommand = versionedCommand("quorum-1", "quorum-idem-1");
        ResearchAgentTaskService.CreateTaskCommand secondCommand = versionedCommand("quorum-2", "quorum-idem-2");
        String firstTaskId = taskService.createTask(firstCommand).taskId();
        String secondTaskId = taskService.createTask(secondCommand).taskId();
        jdbcTemplate.update("""
                update research_agent_task
                set logical_task_key = 'logical-quorum', quorum_group_key = 'quorum-group',
                    candidate_quorum = 2, candidate_slot = case when id = ? then 1 else 2 end
                where id in (?, ?)
                """, firstTaskId, firstTaskId, secondTaskId);

        taskService.claimTask(new ResearchAgentTaskService.ClaimCommand(firstTaskId, "worker-1", 30));
        taskService.claimTask(new ResearchAgentTaskService.ClaimCommand(secondTaskId, "worker-2", 30));
        assertThat(jdbcTemplate.queryForObject(
                "select active_task_id from research_cell where id = ?", String.class, cellId))
                .isEqualTo("quorum-group");

        jdbcTemplate.update(
                "update research_agent_task set lease_expires_at = timestampadd(second, -1, current_timestamp) where id = ?",
                firstTaskId);
        taskService.expireLeases();
        assertThat(jdbcTemplate.queryForObject(
                "select active_task_id from research_cell where id = ?", String.class, cellId))
                .isEqualTo("quorum-group");

        jdbcTemplate.update(
                "update research_agent_task set lease_expires_at = timestampadd(second, -1, current_timestamp) where id = ?",
                secondTaskId);
        taskService.expireLeases();
        assertThat(jdbcTemplate.queryForObject(
                "select active_task_id from research_cell where id = ?", String.class, cellId))
                .isNull();
    }

    private ResearchAgentTaskService.CreateTaskCommand versionedCommand(String taskKey, String idempotencyKey) {
        return new ResearchAgentTaskService.CreateTaskCommand(
                runId, taskKey, idempotencyKey, 1, "DEEP_CELL", "entity-1", "branch-main", 2, 3,
                List.of("entity-1:method"), Map.of("llm_calls", 2),
                List.of(new ResearchAgentTaskService.TargetCellBinding("entity-1:method", 3)),
                new ResearchAgentTaskService.TaskExecutionContext(
                        "research-default", Map.of("allow_workspace_sources", true), Map.of("query", "test question"))
        );
    }

    private ResearchAgentTaskService.CreateTaskCommand command(String taskKey, String idempotencyKey) {
        return new ResearchAgentTaskService.CreateTaskCommand(
                runId, taskKey, idempotencyKey, 1, "DEEP_CELL", "entity-1", "branch-main",
                2, 3, List.of("entity-1:method"), Map.of("llm_calls", 2)
        );
    }
}
