package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ResearchAgentTaskCoordinatorServiceTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ResearchAgentTaskCoordinatorService coordinator;
    @Autowired private ResearchAgentTaskService taskService;
    @Autowired private ResearchAgentAdvancementTaskizationService advancementTaskization;
    @Autowired private ResearchAgentGapProjectionService gapProjection;
    @Autowired private ResearchAgentRepairAdvancementService repairAdvancement;
    @Autowired private ResearchAgentCoordinatorSnapshotService coordinatorSnapshot;
    @Autowired private ResearchAgentCoordinatorTickService coordinatorTick;
    @SpyBean private ResearchAgentCoordinatorTickFaultInjector coordinatorTickFaults;
    @SpyBean private ResearchBudgetAndCheckpointService budgetService;
    @SpyBean private ResearchAgentCommandOutboxService outboxService;
    @MockBean private ResearchAgentExternalEvidencePolicy externalEvidencePolicy;

    private String runId;
    private String workspaceId;

    @BeforeEach
    void setUp() throws Exception {
        workspaceId = Ids.newId();
        String parentTaskId = Ids.newId();
        runId = Ids.newId();
        String fileId = Ids.newId();
        String sourceId = Ids.newId();
        String snapshotId = Ids.newId();
        String chunkId = Ids.newId();
        String rowId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', 'coordinator-test', 'ACTIVE')", workspaceId);
        jdbcTemplate.update("insert into task(id, workspace_id, task_type, task_status, target_type, target_id) values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)", parentTaskId, workspaceId, runId);
        jdbcTemplate.update("insert into file_object(id, workspace_id, object_key, sha256, file_size) values (?, ?, ?, ?, 20)", fileId, workspaceId, "source/test.txt", "a".repeat(64));
        jdbcTemplate.update("insert into source(id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status) values (?, ?, ?, 'Trusted source', 'TEXT', 'READY', 'READY', 'READY')", sourceId, workspaceId, fileId);
        jdbcTemplate.update("insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status) values (?, ?, ?, 1, 'source/test.txt', ?, 'READY', 'READY')", snapshotId, sourceId, fileId, "b".repeat(64));
        jdbcTemplate.update("insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no, content, token_estimate) values (?, ?, ?, ?, 1, 'The method is documented.', 5)", chunkId, workspaceId, sourceId, snapshotId);
        jdbcTemplate.update("insert into source_window(id, source_chunk_id, window_no, content) values (?, ?, 1, 'The method is documented.')", Ids.newId(), chunkId);
        jdbcTemplate.update("""
                insert into research_run(
                    id, workspace_id, task_id, question, profile_key, source_scope_json,
                    research_intent_json, control_pack_json, retrieval_mode, status, agent_execution_mode)
                values (?, ?, ?, 'How does the method work?', 'DEFAULT', ?, ?, ?,
                    'WEB_PLUS_SEEDS', 'RUNNING', 'INCREMENTAL_V1')
                """, runId, workspaceId, parentTaskId, objectMapper.writeValueAsString(List.of(sourceId)),
                objectMapper.writeValueAsString(Map.of(
                        "research_goal", "Preserve the user's research goal",
                        "deliverable_format", "decision memo",
                        "constraints", List.of("Only use auditable evidence"),
                        "time_range", "2024-2026",
                        "depth", "DEEP",
                        "research_type", "TECHNICAL")),
                objectMapper.writeValueAsString(Map.of(
                        "pack_type", "RESEARCH_AGENT",
                        "target_key", "DEFAULT",
                        "task_neighborhood", "RESEARCH_DEFAULT",
                        "style_constraints", List.of("concise"),
                        "structure_constraints", List.of("include limitations"),
                        "terminology_policy", List.of("use canonical terms"),
                        "forbidden_patterns", List.of("unsupported certainty"),
                        "evidence_policy", List.of("server-snapshot-only"))));
        jdbcTemplate.update("insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'CANDIDATE_READY')", rowId, runId);
        insertCell(rowId, "entity-1:method", "method");
        insertCell(rowId, "entity-1:evidence", "evidence");
    }

    @Test
    void shouldCreateReserveAndEnqueueOnceThenReplayIdempotently() {
        ResearchAgentTaskCoordinatorService.CoordinatorReceipt first = coordinator.planAndEnqueue(runId);
        ResearchAgentTaskCoordinatorService.CoordinatorReceipt replay = coordinator.planAndEnqueue(runId);

        assertThat(first.createdTaskCount()).isEqualTo(1);
        assertThat(first.enqueuedCommandCount()).isEqualTo(1);
        assertThat(first.scopedCellCount()).isEqualTo(2);
        assertThat(replay.createdTaskCount()).isZero();
        assertThat(replay.idempotentReplayCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_budget_reservation where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_outbox where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select execution_context_json from research_agent_task where research_run_id = ?", String.class, runId))
                .contains("The method is documented.", "allow_external_search", "false");
    }

    @Test
    void shouldGrantExternalToolScopeOnlyWhenTheServerPolicyEnablesIt() throws Exception {
        when(externalEvidencePolicy.enabled()).thenReturn(true);

        coordinator.planAndEnqueue(runId);

        String context = jdbcTemplate.queryForObject(
                "select execution_context_json from research_agent_task where research_run_id = ?", String.class, runId);
        var sourcePolicy = objectMapper.readTree(context).path("source_policy");
        assertThat(sourcePolicy.path("allow_external_search").asBoolean()).isTrue();
        assertThat(sourcePolicy.path("allow_external_fetch").asBoolean()).isTrue();
    }

    @Test
    void shouldBindCreatedTasksToTheCanonicalCellBranchId() {
        String branchId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_branch(id, research_run_id, branch_key, branch_reason, branch_status)
                values (?, ?, 'main', 'test', 'ACTIVE')
                """, branchId, runId);
        jdbcTemplate.update("update research_cell set branch_id = ? where research_run_id = ?", branchId, runId);

        coordinator.planAndEnqueue(runId);

        assertThat(jdbcTemplate.queryForObject(
                "select distinct branch_id from research_agent_task where research_run_id = ?",
                String.class, runId)).isEqualTo(branchId);
    }

    @Test
    void shouldFanOutAHighRiskCellIntoTwoIndependentDurableCandidateSlots() {
        jdbcTemplate.update("update research_cell set high_risk = true where research_run_id = ? and cell_key = 'entity-1:method'", runId);

        ResearchAgentTaskCoordinatorService.CoordinatorReceipt first = coordinator.planAndEnqueueForWave(runId, 1);
        ResearchAgentTaskCoordinatorService.CoordinatorReceipt replay = coordinator.planAndEnqueueForWave(runId, 1);

        assertThat(first.createdTaskCount()).isEqualTo(3);
        assertThat(first.enqueuedCommandCount()).isEqualTo(3);
        assertThat(first.scopedCellCount()).isEqualTo(2);
        assertThat(replay.createdTaskCount()).isZero();
        assertThat(replay.idempotentReplayCount()).isEqualTo(3);
        List<Map<String, Object>> quorumTasks = jdbcTemplate.queryForList("""
                select id, role, quorum_group_key, candidate_quorum, candidate_slot
                from research_agent_task
                where research_run_id = ? and candidate_quorum = 2
                order by candidate_slot
                """, runId);
        assertThat(quorumTasks).hasSize(2);
        assertThat(quorumTasks).extracting(row -> ((Number) row.get("candidate_slot")).intValue())
                .containsExactly(1, 2);
        assertThat(quorumTasks).extracting(row -> String.valueOf(row.get("role")))
                .containsExactly("DEEP_CELL", "COUNTERFACTUAL");
        assertThat(quorumTasks).extracting(row -> String.valueOf(row.get("quorum_group_key")))
                .doesNotContain("null").containsOnly(String.valueOf(quorumTasks.get(0).get("quorum_group_key")));
        assertThat(quorumTasks).extracting(row -> String.valueOf(row.get("id"))).doesNotHaveDuplicates();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_budget_reservation where research_run_id = ?", Integer.class, runId))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_outbox where research_run_id = ?", Integer.class, runId))
                .isEqualTo(3);
    }

    @Test
    void shouldPersistHigherPriorityForHighRiskBundles() {
        jdbcTemplate.update("update research_cell set high_risk = true where research_run_id = ? and cell_key = 'entity-1:method'", runId);

        coordinator.planAndEnqueueForWave(runId, 1);

        List<Map<String, Object>> tasks = jdbcTemplate.queryForList("""
                select target_cells_json, priority_score, priority_reason
                from research_agent_task
                where research_run_id = ?
                order by priority_score desc, candidate_slot
                """, runId);
        assertThat(tasks).hasSize(3);
        assertThat(tasks.subList(0, 2))
                .allSatisfy(task -> {
                    assertThat(String.valueOf(task.get("target_cells_json"))).contains("entity-1:method");
                    assertThat(String.valueOf(task.get("priority_reason"))).contains("HIGH_RISK");
                });
        assertThat(((Number) tasks.get(1).get("priority_score")).intValue())
                .isGreaterThan(((Number) tasks.get(2).get("priority_score")).intValue());
    }

    @Test
    void shouldClaimAHighRiskCandidateSlotWithAQuorumBoundV2Snapshot() throws Exception {
        jdbcTemplate.update("update research_cell set high_risk = true where research_run_id = ? and cell_key = 'entity-1:method'", runId);
        coordinator.planAndEnqueueForWave(runId, 1);
        String taskId = jdbcTemplate.queryForObject("""
                select id from research_agent_task
                where research_run_id = ? and candidate_quorum = 2 and candidate_slot = 1
                """, String.class, runId);

        ResearchAgentTaskService.ClaimedTask claimed = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(taskId, "quorum-worker-1", 60));
        var snapshot = objectMapper.readTree(claimed.taskSnapshotJson());

        assertThat(snapshot.path("schema_version").asText()).isEqualTo("research-agent-task-snapshot.v3");
        assertThat(snapshot.path("logical_task_key").asText()).startsWith("deep-cell:");
        assertThat(snapshot.path("quorum_group_key").asText()).startsWith("quorum:");
        assertThat(snapshot.path("candidate_quorum").asInt()).isEqualTo(2);
        assertThat(snapshot.path("candidate_slot").asInt()).isEqualTo(1);
        assertThat(snapshot.path("high_risk").asBoolean()).isTrue();
        assertThat(snapshot.path("research_intent").path("research_goal").asText())
                .isEqualTo("Preserve the user's research goal");
        assertThat(snapshot.path("research_intent").path("depth").asText()).isEqualTo("DEEP");
        assertThat(snapshot.path("control_pack").path("style_constraints").get(0).asText())
                .isEqualTo("concise");
        assertThat(snapshot.path("control_pack").path("forbidden_patterns").get(0).asText())
                .isEqualTo("unsupported certainty");
        assertThat(claimed.snapshotDigest()).isEqualTo(snapshot.path("snapshot_digest").asText());
    }

    @Test
    void shouldAllowBothCandidateSlotsToHoldIndependentLeasesForOneQuorumCell() {
        jdbcTemplate.update("update research_cell set high_risk = true where research_run_id = ? and cell_key = 'entity-1:method'", runId);
        coordinator.planAndEnqueueForWave(runId, 1);
        List<String> taskIds = jdbcTemplate.queryForList("""
                select id from research_agent_task
                where research_run_id = ? and candidate_quorum = 2
                order by candidate_slot
                """, String.class, runId);

        ResearchAgentTaskService.ClaimedTask first = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(taskIds.get(0), "quorum-worker-1", 60));
        ResearchAgentTaskService.ClaimedTask second = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(taskIds.get(1), "quorum-worker-2", 60));

        assertThat(first.taskId()).isNotEqualTo(second.taskId());
        assertThat(first.fencingToken()).isEqualTo(1);
        assertThat(second.fencingToken()).isEqualTo(1);
        String group = jdbcTemplate.queryForObject(
                "select quorum_group_key from research_agent_task where id = ?", String.class, first.taskId());
        assertThat(jdbcTemplate.queryForObject("""
                select active_task_id from research_cell
                where research_run_id = ? and cell_key = 'entity-1:method'
                """, String.class, runId)).isEqualTo(group);
    }

    @Test
    void shouldPartitionTrustedSourcesDeterministicallyAcrossHighRiskCandidateSlots() throws Exception {
        String firstSource = jdbcTemplate.queryForObject(
                "select id from source where workspace_id = ? order by id limit 1", String.class, workspaceId);
        String secondSource = insertReadySource("Independent quorum source");
        jdbcTemplate.update("update research_run set source_scope_json = ? where id = ?",
                objectMapper.writeValueAsString(List.of(secondSource, firstSource)), runId);
        jdbcTemplate.update("update research_cell set high_risk = true where research_run_id = ? and cell_key = 'entity-1:method'", runId);

        coordinator.planAndEnqueueForWave(runId, 1);

        List<String> contexts = jdbcTemplate.queryForList("""
                select execution_context_json from research_agent_task
                where research_run_id = ? and candidate_quorum = 2 order by candidate_slot
                """, String.class, runId);
        var firstPolicy = objectMapper.readTree(contexts.get(0)).path("source_policy").path("source_scope");
        var secondPolicy = objectMapper.readTree(contexts.get(1)).path("source_policy").path("source_scope");
        assertThat(firstPolicy).hasSize(1);
        assertThat(secondPolicy).hasSize(1);
        assertThat(firstPolicy.get(0).path("source_id").asText())
                .isNotEqualTo(secondPolicy.get(0).path("source_id").asText());
        assertThat(List.of(firstPolicy.get(0).path("source_id").asText(), secondPolicy.get(0).path("source_id").asText()))
                .containsExactlyInAnyOrder(firstSource, secondSource);
    }

    @Test
    void shouldReadAuthoritativeCoordinatorSnapshotUnderIncrementalRunLock() {
        var initial = coordinatorSnapshot.snapshot(runId);
        assertThat(initial.checkpointSeq()).isZero();
        assertThat(initial.currentWaveNo()).isEqualTo(1);
        assertThat(initial.taskCount()).isZero();
        assertThat(initial.activeTaskCount()).isZero();

        coordinator.planAndEnqueueForWave(runId, 2);
        var afterTaskization = coordinatorSnapshot.snapshot(runId);
        assertThat(afterTaskization.currentWaveNo()).isEqualTo(2);
        assertThat(afterTaskization.taskCount()).isEqualTo(1);
        assertThat(afterTaskization.activeTaskCount()).isEqualTo(1);
    }

    @Test
    void shouldAutomaticallyOpenInitialWaveOnceThenNoopWhileTasksAreActive() {
        var first = coordinatorTick.tick(runId, "scheduler-a");
        var replay = coordinatorTick.tick(runId, "scheduler-b");

        assertThat(first.outcome()).isEqualTo("INITIAL_WAVE_TASKIZED");
        assertThat(first.taskization().createdTaskCount()).isEqualTo(1);
        assertThat(replay.outcome()).isEqualTo("ACTIVE_NOOP");
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ?", Integer.class, runId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_outbox where research_run_id = ?", Integer.class, runId))
                .isEqualTo(1);
    }

    @Test
    void shouldRollbackInitialWaveAndRecoverOnTheNextCoordinatorTickAfterPreCommitFailure() {
        doThrow(new IllegalStateException("injected coordinator crash"))
                .when(coordinatorTickFaults).checkpoint(ResearchAgentCoordinatorTickFaultInjector.Stage.AFTER_INITIAL_TASKIZATION);

        assertThatThrownBy(() -> coordinatorTick.tick(runId, "scheduler-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected coordinator crash");
        assertAgentStateIsEmpty();

        reset(coordinatorTickFaults);
        var recovered = coordinatorTick.tick(runId, "scheduler-b");
        assertThat(recovered.outcome()).isEqualTo("INITIAL_WAVE_TASKIZED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ?", Integer.class, runId))
                .isEqualTo(1);
    }

    @Test
    void shouldAutomaticallyAdvanceAnExhaustedFailedWaveIntoServerDerivedRepairs() {
        coordinator.planAndEnqueueForWave(runId, 1);
        jdbcTemplate.update("update research_agent_task set status = 'FAILED', terminal_reason = 'LEASE_RETRY_EXHAUSTED', terminal_at = current_timestamp where research_run_id = ?", runId);
        String originalSource = jdbcTemplate.queryForObject("select id from source where workspace_id = ?", String.class, workspaceId);
        String independentSource = insertReadySource("Independent scheduler repair source");
        jdbcTemplate.update("update research_run set source_scope_json = ? where id = ?",
                "[\"" + originalSource + "\",\"" + independentSource + "\"]", runId);

        var first = coordinatorTick.tick(runId, "scheduler-a");
        var replay = coordinatorTick.tick(runId, "scheduler-b");
        jdbcTemplate.update("update research_agent_task set status = 'SUBMITTED', terminal_at = current_timestamp where research_run_id = ? and role = 'COUNTERFACTUAL'", runId);
        var settledRepairWave = coordinatorTick.tick(runId, "scheduler-c");

        assertThat(first.outcome()).isEqualTo("FAILED_WAVE_REPAIR_TASKIZED");
        assertThat(replay.outcome()).isEqualTo("ACTIVE_NOOP");
        assertThat(settledRepairWave.outcome()).isEqualTo("RUN_FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_run where id = ?", String.class, runId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_task where research_run_id = ? and status = 'SUBMITTED'",
                Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_budget_reservation where research_run_id = ? and state = 'RESERVED'",
                Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_run_advancement where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_checkpoint where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ? and role = 'COUNTERFACTUAL'", Integer.class, runId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_outbox where research_run_id = ?", Integer.class, runId)).isEqualTo(3);
    }

    @Test
    void shouldFailRunWhenServerDerivedRepairPolicyHasNoSafeTarget() {
        coordinator.planAndEnqueueForWave(runId, 1);
        jdbcTemplate.update("""
                update research_agent_task
                set status = 'FAILED', terminal_reason = 'LEASE_RETRY_EXHAUSTED',
                    terminal_at = current_timestamp
                where research_run_id = ?
                """, runId);
        jdbcTemplate.update("""
                update research_cell set repair_count = 2, active_task_id = null
                where research_run_id = ?
                """, runId);

        var receipt = coordinatorTick.tick(runId, "scheduler-stop");

        assertThat(receipt.outcome()).isEqualTo("FAILED_WAVE_STOPPED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_run where id = ?", String.class, runId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForMap(
                "select task_status, progress_phase from task where target_id = ?", runId))
                .containsEntry("task_status", "FAILED")
                .containsEntry("progress_phase", "RESEARCH_FAILED");
    }

    @Test
    void shouldAutomaticallyFinalizeATerminalCitationGatedLedger() {
        coordinator.planAndEnqueueForWave(runId, 1);
        jdbcTemplate.update("update research_agent_task set status = 'SUBMITTED', terminal_at = current_timestamp where research_run_id = ?", runId);
        jdbcTemplate.update("update research_cell set cell_status = 'VERIFIED', candidate_value = 'verified', evidence_refs_json = '[\"evidence-1\"]' where research_run_id = ?", runId);

        var receipt = coordinatorTick.tick(runId, "scheduler-finalizer");

        assertThat(receipt.outcome()).isEqualTo("RUN_FINALIZED");
        assertThat(jdbcTemplate.queryForObject("select status from research_run where id = ?", String.class, runId)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_report_artifact where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void shouldRejectCoordinatorSnapshotForLegacyRun() {
        jdbcTemplate.update("update research_run set agent_execution_mode = 'SEQUENTIAL_V1' where id = ?", runId);

        assertThatThrownBy(() -> coordinatorSnapshot.snapshot(runId))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_COORDINATOR_SNAPSHOT_INVALID");
    }

    @Test
    void shouldAtomicallyAdvanceAndTaskizeOneNextWaveThenReplayWithoutDuplicates() {
        var first = advancementTaskization.advanceAndTaskize(advanceCommand());
        var replay = advancementTaskization.advanceAndTaskize(advanceCommand());

        assertThat(first.advancement().checkpointSeq()).isEqualTo(1);
        assertThat(first.taskization().createdTaskCount()).isEqualTo(1);
        assertThat(first.taskization().enqueuedCommandCount()).isEqualTo(1);
        assertThat(replay.advancement().idempotentReplay()).isTrue();
        assertThat(replay.taskization().createdTaskCount()).isZero();
        assertThat(replay.taskization().idempotentReplayCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select wave_no from research_agent_task where research_run_id = ?", Integer.class, runId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_budget_reservation where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_outbox where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void shouldCreateReplayableCounterfactualWithServerCheckedIndependentSourceScope() {
        when(externalEvidencePolicy.enabled()).thenReturn(true);
        String excludedSourceId = jdbcTemplate.queryForObject(
                "select id from source where workspace_id = ? order by id limit 1", String.class, workspaceId);
        String independentSourceId = insertReadySource("Independent source");
        jdbcTemplate.update("update research_run set source_scope_json = ? where id = ?",
                "[\"" + excludedSourceId + "\",\"" + independentSourceId + "\"]", runId);
        var command = new ResearchAgentTaskCoordinatorService.CounterfactualRepairCommand(
                runId, 0, 2, List.of(new ResearchAgentTaskCoordinatorService.CounterfactualTarget(
                        "entity-1:method", "sha256:repair-method", List.of(excludedSourceId))));

        var first = coordinator.planCounterfactualRepairs(command);
        var replay = coordinator.planCounterfactualRepairs(command);

        assertThat(first.createdTaskCount()).isEqualTo(1);
        assertThat(first.enqueuedCommandCount()).isEqualTo(1);
        assertThat(replay.createdTaskCount()).isZero();
        assertThat(replay.idempotentReplayCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select role from research_agent_task where research_run_id = ?", String.class, runId))
                .isEqualTo("COUNTERFACTUAL");
        String context = jdbcTemplate.queryForObject("select execution_context_json from research_agent_task where research_run_id = ?", String.class, runId);
        assertThat(context).contains("excluded_source_ids", excludedSourceId, "Independent source",
                        "\"allow_external_search\":true", "\"allow_external_fetch\":true")
                .doesNotContain("Trusted source");
        assertThat(jdbcTemplate.queryForObject("select repair_count from research_cell where research_run_id = ? and cell_key = 'entity-1:method'", Integer.class, runId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_budget_reservation where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_outbox where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void shouldCreateWebOnlyCounterfactualWithoutWorkspaceSources() throws Exception {
        when(externalEvidencePolicy.enabled()).thenReturn(true);
        jdbcTemplate.update("""
                update research_run
                set source_scope_json = '[]', retrieval_mode = 'WEB_ONLY'
                where id = ?
                """, runId);
        var command = new ResearchAgentTaskCoordinatorService.CounterfactualRepairCommand(
                runId, 0, 2, List.of(new ResearchAgentTaskCoordinatorService.CounterfactualTarget(
                        "entity-1:method", "sha256:web-only-repair", List.of("external:prior-evidence"))));

        var receipt = coordinator.planCounterfactualRepairs(command);

        assertThat(receipt.createdTaskCount()).isEqualTo(1);
        String context = jdbcTemplate.queryForObject("""
                select execution_context_json from research_agent_task
                where research_run_id = ? and role = 'COUNTERFACTUAL'
                """, String.class, runId);
        var sourcePolicy = objectMapper.readTree(context).path("source_policy");
        assertThat(sourcePolicy.path("retrieval_mode").asText()).isEqualTo("WEB_ONLY");
        assertThat(sourcePolicy.path("source_scope")).isEmpty();
        assertThat(sourcePolicy.path("allow_external_search").asBoolean()).isTrue();
        assertThat(sourcePolicy.path("allow_external_fetch").asBoolean()).isTrue();
        assertThat(sourcePolicy.path("excluded_source_ids").get(0).asText())
                .isEqualTo("external:prior-evidence");
    }

    @Test
    void shouldDeriveRepairGapAndSourceExclusionFromFailedTaskSnapshot() {
        var taskization = coordinator.planAndEnqueueForWave(runId, 1);
        assertThat(taskization.createdTaskCount()).isEqualTo(1);
        jdbcTemplate.update("update research_agent_task set status = 'FAILED', terminal_reason = 'LEASE_RETRY_EXHAUSTED', terminal_at = current_timestamp where research_run_id = ?", runId);
        String trustedSourceId = jdbcTemplate.queryForObject("select id from source where workspace_id = ?", String.class, workspaceId);

        var gaps = gapProjection.projectRepairableGaps(runId, 1);

        assertThat(gaps).hasSize(2);
        assertThat(gaps).extracting(ResearchAgentGapProjectionService.RepairGap::cellKey)
                .containsExactly("entity-1:evidence", "entity-1:method");
        assertThat(gaps).allSatisfy(gap -> {
            assertThat(gap.reasonDigest()).startsWith("sha256:");
            assertThat(gap.failedTaskIds()).hasSize(1);
            assertThat(gap.excludedSourceIds()).containsExactly(trustedSourceId);
            assertThat(gap.noActiveTask()).isTrue();
        });
    }

    @Test
    void shouldAtomicallyAdvanceFromFailedWaveAndTaskizeCounterfactualRepairsOnReplay() {
        coordinator.planAndEnqueueForWave(runId, 1);
        jdbcTemplate.update("update research_agent_task set status = 'FAILED', terminal_reason = 'LEASE_RETRY_EXHAUSTED', terminal_at = current_timestamp where research_run_id = ?", runId);
        String originalSource = jdbcTemplate.queryForObject("select id from source where workspace_id = ?", String.class, workspaceId);
        String independentSource = insertReadySource("Independent repair source");
        jdbcTemplate.update("update research_run set source_scope_json = ? where id = ?",
                "[\"" + originalSource + "\",\"" + independentSource + "\"]", runId);
        var command = repairAdvanceCommand();

        var first = repairAdvancement.advanceAndTaskize(command);
        var replay = repairAdvancement.advanceAndTaskize(command);

        assertThat(first.decisionKind()).isEqualTo(ResearchAgentRepairStopPolicy.DecisionKind.COUNTERFACTUAL);
        assertThat(first.advancement().checkpointSeq()).isEqualTo(1);
        assertThat(first.taskization().createdTaskCount()).isEqualTo(2);
        assertThat(replay.advancement().idempotentReplay()).isTrue();
        assertThat(replay.taskization().createdTaskCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_run_advancement where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_checkpoint where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ? and role = 'COUNTERFACTUAL'", Integer.class, runId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_outbox where research_run_id = ?", Integer.class, runId)).isEqualTo(3);
    }

    @Test
    void shouldTaskizeAnOpenQuorumVerifierRepairWithoutRequiringAFailedTask() throws Exception {
        coordinator.planAndEnqueueForWave(runId, 1);
        jdbcTemplate.update("""
                update research_agent_task
                set status = 'SUBMITTED', terminal_at = current_timestamp
                where research_run_id = ?
                """, runId);
        String conflictedSource = jdbcTemplate.queryForObject(
                "select id from source where workspace_id = ? order by id limit 1", String.class, workspaceId);
        String independentSource = insertReadySource("Independent quorum repair source");
        jdbcTemplate.update("update research_run set source_scope_json = ? where id = ?",
                objectMapper.writeValueAsString(List.of(conflictedSource, independentSource)), runId);
        String evidenceKey = "quorum-conflict-evidence";
        jdbcTemplate.update("""
                insert into source_evidence(id, research_run_id, evidence_key, source_id, relation_type, snapshot_status)
                values (?, ?, ?, ?, 'SUPPORTS', 'WORKSPACE')
                """, Ids.newId(), runId, evidenceKey, conflictedSource);
        jdbcTemplate.update("""
                insert into research_verifier_decision(
                    id, research_run_id, branch_id, decision_scope, decision_type, reason_code,
                    target_id, evidence_ids_json, action_text, decision_status, notes_json)
                values (?, ?, 'branch-quorum-2', 'CELL', 'QUORUM_REPAIR_REQUIRED',
                    'QUORUM_VALUE_CONFLICT', 'entity-1:method', ?, 'COUNTERFACTUAL_REPAIR', 'OPEN', '{}')
                """, Ids.newId(), runId, objectMapper.writeValueAsString(List.of(evidenceKey)));

        ResearchAgentCoordinatorTickService.TickReceipt receipt = coordinatorTick.tick(runId, "scheduler-quorum-repair");

        assertThat(receipt.outcome()).isEqualTo("VERIFIER_REPAIR_TASKIZED");
        assertThat(receipt.taskization().createdTaskCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task
                where research_run_id = ? and wave_no = 2 and role = 'COUNTERFACTUAL'
                """, Integer.class, runId)).isEqualTo(1);
        String context = jdbcTemplate.queryForObject("""
                select execution_context_json from research_agent_task
                where research_run_id = ? and wave_no = 2 and role = 'COUNTERFACTUAL'
                """, String.class, runId);
        var sourcePolicy = objectMapper.readTree(context).path("source_policy");
        assertThat(sourcePolicy.path("source_scope")).hasSize(1);
        assertThat(sourcePolicy.path("source_scope").get(0).path("source_id").asText())
                .isEqualTo(independentSource);
        assertThat(sourcePolicy.path("excluded_source_ids").get(0).asText())
                .isEqualTo(conflictedSource);
    }

    @Test
    void shouldRejectFrozenOrForeignCounterfactualTargetsBeforeTaskCreation() {
        jdbcTemplate.update("update research_cell set cell_status = 'FROZEN' where research_run_id = ? and cell_key = 'entity-1:method'", runId);
        var frozen = new ResearchAgentTaskCoordinatorService.CounterfactualRepairCommand(
                runId, 0, 2, List.of(new ResearchAgentTaskCoordinatorService.CounterfactualTarget(
                        "entity-1:method", "sha256:frozen", List.of())));
        assertThatThrownBy(() -> coordinator.planCounterfactualRepairs(frozen))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_REPAIR_TARGET_INVALID");

        var foreign = new ResearchAgentTaskCoordinatorService.CounterfactualRepairCommand(
                runId, 0, 2, List.of(new ResearchAgentTaskCoordinatorService.CounterfactualTarget(
                        "foreign:cell", "sha256:foreign", List.of())));
        assertThatThrownBy(() -> coordinator.planCounterfactualRepairs(foreign))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_REPAIR_TARGET_INVALID");
        assertAgentStateIsEmpty();
    }

    @Test
    void shouldRollbackCounterfactualTaskAndReservationWhenOutboxEnqueueFails() {
        doThrow(new IllegalStateException("injected counterfactual outbox failure"))
                .when(outboxService).enqueue(anyString());
        var command = new ResearchAgentTaskCoordinatorService.CounterfactualRepairCommand(
                runId, 0, 2, List.of(new ResearchAgentTaskCoordinatorService.CounterfactualTarget(
                        "entity-1:method", "sha256:repair-method", List.of())));

        assertThatThrownBy(() -> coordinator.planCounterfactualRepairs(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected counterfactual outbox failure");

        assertAgentStateIsEmpty();
    }

    @Test
    void shouldRollbackAdvancementCheckpointAndTaskizationWhenOutboxEnqueueFails() {
        doThrow(new IllegalStateException("injected advance-and-taskize outbox failure"))
                .when(outboxService).enqueue(anyString());

        assertThatThrownBy(() -> advancementTaskization.advanceAndTaskize(advanceCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected advance-and-taskize outbox failure");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_run_advancement where research_run_id = ?", Integer.class, runId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_checkpoint where research_run_id = ?", Integer.class, runId))
                .isZero();
        assertAgentStateIsEmpty();
    }

    @Test
    void shouldRejectNonIncrementalRunWithoutCreatingAgentState() {
        jdbcTemplate.update("update research_run set agent_execution_mode = 'SEQUENTIAL_V1' where id = ?", runId);
        assertThatThrownBy(() -> coordinator.planAndEnqueue(runId))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_COORDINATOR_MODE_INVALID");
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ?", Integer.class, runId)).isZero();
    }

    @Test
    void shouldRollbackTaskWhenBudgetReservationFails() {
        doThrow(new IllegalStateException("injected reservation failure"))
                .when(budgetService).reserve(any(ResearchBudgetAndCheckpointService.ReserveCommand.class));

        assertThatThrownBy(() -> coordinator.planAndEnqueue(runId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected reservation failure");

        assertAgentStateIsEmpty();
    }

    @Test
    void shouldRollbackTaskAndReservationWhenOutboxEnqueueFails() {
        doThrow(new IllegalStateException("injected outbox failure"))
                .when(outboxService).enqueue(anyString());

        assertThatThrownBy(() -> coordinator.planAndEnqueue(runId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected outbox failure");

        assertAgentStateIsEmpty();
    }

    @Test
    void shouldNotTaskizeVerifiedOrFrozenCells() {
        jdbcTemplate.update("update research_cell set cell_status = 'VERIFIED' where research_run_id = ? and column_key = 'method'", runId);
        jdbcTemplate.update("update research_cell set cell_status = 'FROZEN' where research_run_id = ? and column_key = 'evidence'", runId);

        var receipt = coordinator.planAndEnqueue(runId);

        assertThat(receipt.scopedCellCount()).isZero();
        assertAgentStateIsEmpty();
    }

    @Test
    void shouldCreateNewTaskWhenPersistedPlanRevisionChanges() {
        var first = coordinator.planAndEnqueue(runId);
        jdbcTemplate.update("update research_cell set plan_revision = 2 where research_run_id = ?", runId);

        var revised = coordinator.planAndEnqueue(runId);

        assertThat(first.createdTaskCount()).isEqualTo(1);
        assertThat(revised.createdTaskCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ?", Integer.class, runId)).isEqualTo(2);
    }

    @Test
    void shouldReserveTenDimensionEnvelope() throws Exception {
        coordinator.planAndEnqueue(runId);
        String taskId = jdbcTemplate.queryForObject(
                "select id from research_agent_task where research_run_id = ?", String.class, runId);

        assertThat(jdbcTemplate.queryForObject(
                "select state from research_budget_reservation where research_agent_task_id = ?", String.class, taskId))
                .isEqualTo("RESERVED");
        String reservedJson = jdbcTemplate.queryForObject(
                "select reserved_json from research_budget_reservation where research_agent_task_id = ?",
                String.class, taskId);
        Map<String, Long> reserved = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                reservedJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Long>>() { });
        assertThat(reserved.keySet()).containsExactlyInAnyOrder(
                "llm_calls", "search_calls", "fetch_calls", "read_calls", "extract_calls",
                "evidence_cards", "evidence_appended", "candidates_submitted",
                "candidate_merges_accepted", "candidate_merges_rejected");
    }

    private void assertAgentStateIsEmpty() {
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ?", Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_budget_reservation where research_run_id = ?", Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_outbox where research_run_id = ?", Integer.class, runId)).isZero();
    }

    private ResearchAgentRunAdvancementService.AdvanceCommand advanceCommand() {
        return new ResearchAgentRunAdvancementService.AdvanceCommand(
                runId, "advance:wave-2", "coordinator-i3", 0, "sha256:" + "c".repeat(64),
                2, 1, 1, 1, "ledger:wave-2", 0, 0, 0,
                Map.of("state", "ok"), Map.of("decision", "NEXT_WAVE"));
    }

    private ResearchAgentRepairAdvancementService.RepairAdvanceCommand repairAdvanceCommand() {
        return new ResearchAgentRepairAdvancementService.RepairAdvanceCommand(
                runId, "repair:failed-wave-1", "coordinator-i4", 0, 1, 2, 1, 1, 1,
                "ledger:repair-wave-1", 1, 0, 0, Map.of("state", "ok"), Map.of("decision", "REPAIR"));
    }

    private void insertCell(String rowId, String cellKey, String columnKey) {
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key, candidate_value,
                    cell_status, repair_count, cell_version, plan_revision, entity_set_version)
                values (?, ?, ?, ?, ?, '', 'CANDIDATE_READY', 0, 0, 1, 1)
                """, Ids.newId(), runId, rowId, cellKey, columnKey);
    }

    private String insertReadySource(String title) {
        String fileId = Ids.newId();
        String sourceId = Ids.newId();
        String snapshotId = Ids.newId();
        String chunkId = Ids.newId();
        jdbcTemplate.update("insert into file_object(id, workspace_id, object_key, sha256, file_size) values (?, ?, ?, ?, 20)",
                fileId, workspaceId, "source/" + sourceId + ".txt", "d".repeat(64));
        jdbcTemplate.update("insert into source(id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status) values (?, ?, ?, ?, 'TEXT', 'READY', 'READY', 'READY')",
                sourceId, workspaceId, fileId, title);
        jdbcTemplate.update("insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status) values (?, ?, ?, 1, ?, ?, 'READY', 'READY')",
                snapshotId, sourceId, fileId, "source/" + sourceId + ".txt", "e".repeat(64));
        jdbcTemplate.update("insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no, content, token_estimate) values (?, ?, ?, ?, 1, ?, 5)",
                chunkId, workspaceId, sourceId, snapshotId, title + " content");
        jdbcTemplate.update("insert into source_window(id, source_chunk_id, window_no, content) values (?, ?, 1, ?)",
                Ids.newId(), chunkId, title + " content");
        return sourceId;
    }
}
