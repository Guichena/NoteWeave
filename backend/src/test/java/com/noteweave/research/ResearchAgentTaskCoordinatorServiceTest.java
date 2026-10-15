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
    @Autowired
    private ResearchAgentLocalReplanRecorder localReplanRecorder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ResearchAgentTaskCoordinatorService coordinator;
    @Autowired private ResearchAgentTaskService taskService;
    @Autowired private ResearchAgentAdvancementTaskizationService advancementTaskization;
    @Autowired private ResearchAgentGapProjectionService gapProjection;
    @Autowired private ResearchAgentRepairAdvancementService repairAdvancement;
    @Autowired private ResearchAgentCoordinatorSnapshotService coordinatorSnapshot;
    @Autowired private ResearchAgentCoordinatorTickService coordinatorTick;
    @Autowired private ResearchDiscoveryLoopService discoveryLoop;
    @Autowired private ResearchAgentFeatureFlagService featureFlags;
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

        assertThat(first.createdTaskCount()).isEqualTo(2);
        assertThat(first.enqueuedCommandCount()).isEqualTo(2);
        assertThat(first.scopedCellCount()).isEqualTo(2);
        assertThat(replay.createdTaskCount()).isZero();
        assertThat(replay.idempotentReplayCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ?", Integer.class, runId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_budget_reservation where research_run_id = ?", Integer.class, runId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_outbox where research_run_id = ?", Integer.class, runId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "select execution_context_json from research_agent_task where research_run_id = ?", String.class, runId))
                .allSatisfy(context -> assertThat(context)
                        .contains("The method is documented.", "allow_external_search", "false"));
    }

    @Test
    void productionDefaultBundlesCellsOfOneEntityIntoOneTaskWithAPerCellBudget() {
        // 生产默认值：同一研究对象的普通单元格共用一次检索与阅读，模型调用预算按单元格数放大
        coordinator.setMaxCellsPerTask(3);
        try {
            ResearchAgentTaskCoordinatorService.CoordinatorReceipt receipt = coordinator.planAndEnqueue(runId);

            assertThat(receipt.createdTaskCount()).isEqualTo(1);
            assertThat(receipt.scopedCellCount()).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject(
                    "select target_cells_json from research_agent_task where research_run_id = ?", String.class, runId))
                    .contains("entity-1:method", "entity-1:evidence");
            assertThat(ResearchAgentTaskCoordinatorService.deepCellBudget(2))
                    .containsEntry("llm_calls", 66L)
                    .containsEntry("search_calls", 3L)
                    .containsEntry("evidence_cards", 12L);
        } finally {
            coordinator.setMaxCellsPerTask(1);
        }
    }

    @Test
    void wideDiscoveryRunsOnlyForComplexPlansByDefault() throws Exception {
        jdbcTemplate.update("update research_run set agent_feature_flags_json = ? where id = ?", "{\"wide_discovery_v1\":true}", runId);
        var gated = new ResearchDiscoveryLoopService(jdbcTemplate, objectMapper, featureFlags, coordinator);
        gated.setWideDiscoveryEffortTiers("COMPLEX");
        assertThat(gated.enabled(runId)).as("no model plan means no wide discovery").isFalse();

        insertLlmPlan("STANDARD");
        assertThat(gated.enabled(runId)).isFalse();
        insertLlmPlan("COMPLEX");
        assertThat(gated.enabled(runId)).isTrue();

        gated.setWideDiscoveryEffortTiers("ANY");
        insertLlmPlan("SIMPLE");
        assertThat(gated.enabled(runId)).isTrue();
    }

    private void insertLlmPlan(String effortTier) throws Exception {
        jdbcTemplate.update("""
                insert into research_matrix_plan(
                    id, research_run_id, planner_version, plan_mode, plan_status,
                    row_count, column_count, cell_count, bounded, reason_codes_json,
                    plan_json, plan_digest, created_at)
                values (?, ?, ?, 'LLM_MATRIX', 'ACTIVE', 1, 2, 2, false, '[]', ?, ?,
                    timestampadd(second, (select count(*) from research_matrix_plan), current_timestamp))
                """, Ids.newId(), runId, "llm-planning.v1-" + effortTier,
                objectMapper.writeValueAsString(Map.of("effort_tier", effortTier)),
                "sha256:" + Integer.toHexString(effortTier.hashCode()).repeat(16).substring(0, 64));
    }

    @Test
    void shouldCarryTargetColumnLabelsFromPlanIntoQueryPolicy() throws Exception {
        jdbcTemplate.update("""
                insert into research_matrix_plan(
                    id, research_run_id, planner_version, plan_mode, plan_status,
                    row_count, column_count, cell_count, bounded, reason_codes_json,
                    plan_json, plan_digest)
                values (?, ?, 'intent-matrix.v2', 'INTENT_MATRIX_V2', 'ACTIVE', 1, 2, 2, false, '[]', ?, ?)
                """, Ids.newId(), runId,
                objectMapper.writeValueAsString(Map.of("columns", List.of(
                        Map.of("key", "method", "label", "Training procedure")))),
                "sha256:" + "2".repeat(64));
        coordinator.planAndEnqueue(runId);

        var contexts = jdbcTemplate.queryForList(
                "select execution_context_json from research_agent_task where research_run_id = ?",
                String.class, runId);
        Map<String, String> labels = new java.util.HashMap<>();
        for (String context : contexts) {
            for (var spec : objectMapper.readTree(context).path("query_policy").path("column_specs")) {
                labels.put(spec.path("key").asText(), spec.path("label").asText());
            }
        }
        // 规划里没有标签的列退回使用列键
        assertThat(labels).containsEntry("method", "Training procedure")
                .containsEntry("evidence", "evidence");
    }

    @Test
    void shouldGrantExternalToolScopeOnlyWhenTheServerPolicyEnablesIt() throws Exception {
        when(externalEvidencePolicy.enabled()).thenReturn(true);

        coordinator.planAndEnqueue(runId);

        var contexts = jdbcTemplate.queryForList(
                "select execution_context_json from research_agent_task where research_run_id = ?",
                String.class, runId);
        assertThat(contexts).hasSize(2).allSatisfy(context -> {
            try {
                var sourcePolicy = objectMapper.readTree(context).path("source_policy");
                assertThat(sourcePolicy.path("allow_external_search").asBoolean()).isTrue();
                assertThat(sourcePolicy.path("allow_external_fetch").asBoolean()).isTrue();
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
    }

    @Test
    void shouldCarryServerParsedDomainAllowlistIntoTheImmutableTaskSnapshot() throws Exception {
        when(externalEvidencePolicy.enabled()).thenReturn(true);
        jdbcTemplate.update("update research_run set research_intent_json = ? where id = ?",
                objectMapper.writeValueAsString(Map.of(
                        "research_goal", "Compare APIs",
                        "constraints", List.of(
                                "SOURCE_DOMAIN_ALLOWLIST: platform.openai.com, docs.anthropic.com, invalid/path",
                                "formal claims require citations"))), runId);

        coordinator.planAndEnqueue(runId);

        var contexts = jdbcTemplate.queryForList(
                "select execution_context_json from research_agent_task where research_run_id = ?",
                String.class, runId);
        assertThat(contexts).hasSize(2).allSatisfy(context -> {
            try {
                var domains = objectMapper.readTree(context).path("source_policy").path("allowed_external_domains");
                assertThat(domains).extracting(node -> node.asText())
                        .containsExactly("docs.anthropic.com", "platform.openai.com");
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
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
        assertThat(afterTaskization.taskCount()).isEqualTo(2);
        assertThat(afterTaskization.activeTaskCount()).isEqualTo(2);
    }

    @Test
    void shouldAutomaticallyOpenInitialWaveOnceThenNoopWhileTasksAreActive() {
        var first = coordinatorTick.tick(runId, "scheduler-a");
        var replay = coordinatorTick.tick(runId, "scheduler-b");

        assertThat(first.outcome()).isEqualTo("INITIAL_WAVE_TASKIZED");
        assertThat(first.taskization().createdTaskCount()).isEqualTo(2);
        assertThat(replay.outcome()).isEqualTo("ACTIVE_NOOP");
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ?", Integer.class, runId))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_outbox where research_run_id = ?", Integer.class, runId))
                .isEqualTo(2);
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
                .isEqualTo(2);
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
        // DR-305 upgrade: this exhausted wave used to fail the Run with the opaque
        // RESEARCH_AGENT_TERMINAL_BARRIER_UNSATISFIED. Missing evidence is now an honest business
        // terminal state, so the Run completes instead of failing.
        assertThat(settledRepairWave.outcome()).isEqualTo("RUN_COMPLETED");
        assertThat(jdbcTemplate.queryForMap("""
                select status, completion_terminal_state from research_run where id = ?
                """, runId))
                .containsEntry("status", "COMPLETED")
                .containsEntry("completion_terminal_state", "INSUFFICIENT_EVIDENCE");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_task where research_run_id = ? and status = 'SUBMITTED'",
                Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_budget_reservation where research_run_id = ? and state = 'RESERVED'",
                Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_run_advancement where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_checkpoint where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ? and role = 'COUNTERFACTUAL'", Integer.class, runId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_outbox where research_run_id = ?", Integer.class, runId)).isEqualTo(4);
    }

    @Test
    void submittedTasksThatLeftCellsWithoutAnswersGetOneRepairEach() {
        coordinator.planAndEnqueueForWave(runId, 1);
        // 合并任务提交成功，但部分单元格没有拿到候选，任务本身不算失败
        jdbcTemplate.update("update research_agent_task set status = 'SUBMITTED', terminal_reason = 'CANDIDATES_PROPOSED', terminal_at = current_timestamp where research_run_id = ?", runId);
        jdbcTemplate.update("update research_cell set cell_status = 'GAP', active_task_id = null where research_run_id = ?", runId);
        String originalSource = jdbcTemplate.queryForObject("select id from source where workspace_id = ?", String.class, workspaceId);
        String independentSource = insertReadySource("Independent gap repair source");
        jdbcTemplate.update("update research_run set source_scope_json = ? where id = ?",
                "[\"" + originalSource + "\",\"" + independentSource + "\"]", runId);
        int gapCells = jdbcTemplate.queryForObject(
                "select count(*) from research_cell where research_run_id = ? and cell_status = 'GAP'", Integer.class, runId);

        var first = coordinatorTick.tick(runId, "scheduler-a");
        jdbcTemplate.update("update research_agent_task set status = 'SUBMITTED', terminal_at = current_timestamp where research_run_id = ? and role = 'COUNTERFACTUAL'", runId);
        var afterRepair = coordinatorTick.tick(runId, "scheduler-b");

        assertThat(first.outcome()).isEqualTo("GAP_REPAIR_TASKIZED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_task where research_run_id = ? and role = 'COUNTERFACTUAL'", Integer.class, runId))
                .isEqualTo(gapCells);
        // 每个单元格只补查一次，补查后仍无结论就如实收尾
        assertThat(afterRepair.outcome()).isEqualTo("RUN_COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "select completion_terminal_state from research_run where id = ?", String.class, runId))
                .isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void repeatedRepairRoundsOnTheSameObservedRevisionGetIncreasingLocalPlanRevisions() throws Exception {
        jdbcTemplate.update("""
                insert into research_matrix_plan(
                    id, research_run_id, planner_version, plan_mode, plan_status,
                    row_count, column_count, cell_count, bounded, reason_codes_json,
                    plan_json, plan_digest)
                values (?, ?, 'test-plan.v1', 'INTENT_MATRIX_V2', 'ACTIVE', 1, 2, 2, false, '[]', '{}', ?)
                """, Ids.newId(), runId, "sha256:" + "1".repeat(64));
        var target = List.of(new ResearchAgentRepairStopPolicy.RepairTarget("entity-1:evidence", "sha256:reason", List.of()));

        // 失败任务补查与缺口补查先后发生，第二轮看到的单元格最大版本可能没变
        localReplanRecorder.record(runId, 2, "sha256:first", "NO_VALID_QUOTE", target);
        localReplanRecorder.record(runId, 2, "sha256:second", "NO_VALID_QUOTE", target);

        assertThat(jdbcTemplate.queryForList(
                "select planner_version from research_matrix_plan where research_run_id = ? and plan_mode = 'LOCAL_REPAIR' order by planner_version",
                String.class, runId)).containsExactly("local-replan.v1:r3", "local-replan.v1:r4");
    }

    @Test
    void shouldLocallyReplanOnlyTheUnresolvedCellAndPersistItsAudit() throws Exception {
        coordinator.planAndEnqueueForWave(runId, 1);
        jdbcTemplate.update("""
                update research_cell
                set cell_status = 'VERIFIED', candidate_value = 'settled method',
                    evidence_refs_json = '["evidence-settled"]', active_task_id = null
                where research_run_id = ? and cell_key = 'entity-1:method'
                """, runId);
        jdbcTemplate.update("""
                update research_agent_task
                set status = 'FAILED', terminal_reason = 'NO_SUPPORTED_CANDIDATE',
                    terminal_at = current_timestamp
                where research_run_id = ? and wave_no = 1
                """, runId);
        String initialPlanDigest = "sha256:" + "1".repeat(64);
        jdbcTemplate.update("""
                insert into research_matrix_plan(
                    id, research_run_id, planner_version, plan_mode, plan_status,
                    row_count, column_count, cell_count, bounded, reason_codes_json,
                    plan_json, plan_digest)
                values (?, ?, 'test-plan.v1', 'INTENT_MATRIX_V2', 'ACTIVE',
                    1, 2, 2, false, '[]', ?, ?)
                """, Ids.newId(), runId,
                objectMapper.writeValueAsString(Map.of("cells", List.of("entity-1:method", "entity-1:evidence"))),
                initialPlanDigest);
        String originalSource = jdbcTemplate.queryForObject(
                "select id from source where workspace_id = ?", String.class, workspaceId);
        String independentSource = insertReadySource("Independent local-replan source");
        jdbcTemplate.update("update research_run set source_scope_json = ? where id = ?",
                objectMapper.writeValueAsString(List.of(originalSource, independentSource)), runId);

        var receipt = coordinatorTick.tick(runId, "scheduler-local-replan");

        assertThat(receipt.outcome()).isEqualTo("FAILED_WAVE_REPAIR_TASKIZED");
        assertThat(receipt.taskization().createdTaskCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select target_cells_json from research_agent_task
                where research_run_id = ? and wave_no = 2
                """, String.class, runId)).isEqualTo("[\"entity-1:evidence\"]");
        assertThat(jdbcTemplate.queryForMap("""
                select cell_status, repair_count from research_cell
                where research_run_id = ? and cell_key = 'entity-1:method'
                """, runId))
                .containsEntry("cell_status", "VERIFIED")
                .containsEntry("repair_count", 0);
        assertThat(jdbcTemplate.queryForMap("""
                select plan_revision_from, plan_revision_to, old_plan_digest,
                       deviation_type, affected_cells_json, selected_repair
                from research_agent_replan_audit where research_run_id = ?
                """, runId))
                .containsEntry("plan_revision_from", 1)
                .containsEntry("plan_revision_to", 2)
                .containsEntry("old_plan_digest", initialPlanDigest)
                .containsEntry("deviation_type", "MARGINAL_GAIN_EXHAUSTED")
                .containsEntry("affected_cells_json", "[\"entity-1:evidence\"]")
                .containsEntry("selected_repair", "COUNTERFACTUAL_RESEARCH");
        String localPlanJson = jdbcTemplate.queryForObject("""
                select plan_json from research_matrix_plan
                where research_run_id = ? and plan_mode = 'LOCAL_REPAIR'
                """, String.class, runId);
        var localPlan = objectMapper.readTree(localPlanJson);
        if (localPlan.isTextual()) localPlan = objectMapper.readTree(localPlan.asText());
        assertThat(localPlan.path("repair_cause").asText()).isEqualTo("NO_VALID_QUOTE");
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
    void shouldCompleteHonestlyWhenFailedTasksOnlyLackedEvidenceAndNoRepairTargetRemains() {
        coordinator.planAndEnqueueForWave(runId, 1);
        jdbcTemplate.update("""
                update research_agent_task
                set status = 'FAILED', terminal_reason = 'NO_SUPPORTED_CANDIDATE',
                    terminal_at = current_timestamp
                where research_run_id = ?
                """, runId);
        jdbcTemplate.update("""
                update research_cell set repair_count = 2, active_task_id = null
                where research_run_id = ?
                """, runId);

        var receipt = coordinatorTick.tick(runId, "scheduler-evidence-stop");

        assertThat(receipt.outcome()).isEqualTo("FAILED_WAVE_STOPPED_COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_run where id = ?", String.class, runId)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "select final_report_markdown from research_run where id = ?", String.class, runId))
                .contains("# 研究报告", "INSUFFICIENT_EVIDENCE");
    }

    @Test
    void shouldFailRunWhenCounterfactualRepairHasNoIndependentSeedSource() {
        coordinator.planAndEnqueueForWave(runId, 1);
        jdbcTemplate.update("""
                update research_agent_task
                set status = 'FAILED', terminal_reason = 'LEASE_RETRY_EXHAUSTED',
                    terminal_at = current_timestamp
                where research_run_id = ?
                """, runId);

        var receipt = coordinatorTick.tick(runId, "scheduler-no-independent-source");

        assertThat(receipt.outcome()).isEqualTo("FAILED_WAVE_STOPPED");
        assertThat(receipt.taskization().createdTaskCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_run where id = ?", String.class, runId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_task where research_run_id = ? and role = 'COUNTERFACTUAL'",
                Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_run_advancement where research_run_id = ?",
                Integer.class, runId)).isEqualTo(1);
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
        assertThat(first.taskization().createdTaskCount()).isEqualTo(2);
        assertThat(first.taskization().enqueuedCommandCount()).isEqualTo(2);
        assertThat(replay.advancement().idempotentReplay()).isTrue();
        assertThat(replay.taskization().createdTaskCount()).isZero();
        assertThat(replay.taskization().idempotentReplayCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "select distinct wave_no from research_agent_task where research_run_id = ?", Integer.class, runId))
                .containsExactly(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ?", Integer.class, runId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_budget_reservation where research_run_id = ?", Integer.class, runId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_outbox where research_run_id = ?", Integer.class, runId)).isEqualTo(2);
    }

    @Test
    void shouldCreateReplayableCounterfactualWithServerCheckedIndependentSourceScope() {
        when(externalEvidencePolicy.enabled()).thenReturn(true);
        jdbcTemplate.update("update research_row set source_title = 'PostgreSQL 17' where research_run_id = ? and row_key = 'entity-1'", runId);
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
                        "\"allow_external_search\":true", "\"allow_external_fetch\":true",
                        "\"entity_label\":\"PostgreSQL 17\"")
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
    void shouldRejectMissingSeedScopeInsteadOfDowngradingWebPlusSeedsToWebOnly() {
        jdbcTemplate.update("update research_run set source_scope_json = '[]' where id = ?", runId);

        assertThatThrownBy(() -> coordinator.planAndEnqueue(runId))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.code()).isEqualTo("RESEARCH_AGENT_COORDINATOR_SOURCE_SCOPE_REQUIRED"));
    }

    @Test
    void shouldDeriveRepairGapAndSourceExclusionFromFailedTaskSnapshot() {
        var taskization = coordinator.planAndEnqueueForWave(runId, 1);
        assertThat(taskization.createdTaskCount()).isEqualTo(2);
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
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_outbox where research_run_id = ?", Integer.class, runId)).isEqualTo(4);
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

        assertThat(first.createdTaskCount()).isEqualTo(2);
        assertThat(revised.createdTaskCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ?", Integer.class, runId)).isEqualTo(4);
    }

    @Test
    void shouldReserveTenDimensionEnvelope() throws Exception {
        coordinator.planAndEnqueue(runId);
        var reservations = jdbcTemplate.queryForList("""
                select state, reserved_json from research_budget_reservation where research_run_id = ?
                """, runId);

        assertThat(reservations).hasSize(2).allSatisfy(reservation -> {
            assertThat(reservation.get("STATE")).isEqualTo("RESERVED");
            try {
                Map<String, Long> reserved = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        String.valueOf(reservation.get("RESERVED_JSON")),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Long>>() { });
                assertThat(reserved.keySet()).containsExactlyInAnyOrder(
                        "llm_calls", "search_calls", "fetch_calls", "read_calls", "extract_calls",
                        "evidence_cards", "evidence_appended", "candidates_submitted",
                        "candidate_merges_accepted", "candidate_merges_rejected");
                assertThat(reserved.get("llm_calls")).isEqualTo(34L);
                assertThat(reserved)
                        .containsEntry("search_calls", 3L)
                        .containsEntry("fetch_calls", 3L)
                        .containsEntry("read_calls", 3L)
                        .containsEntry("extract_calls", 3L);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
    }

    @Test
    void discoveryLoopShouldAlternatePopulateAndExpansionThenStopAfterTwoEmptyRounds() throws Exception {
        jdbcTemplate.update("update research_run set agent_feature_flags_json = ? where id = ?", "{\"wide_discovery_v1\":true}", runId);
        var first = discoveryLoop.advance(runId, 1);
        assertThat(first.outcome()).isEqualTo("EXPAND_ROWS");
        assertThat(discoveryLoop.advance(runId, 1).outcome()).isEqualTo("DISCOVERY_PENDING");
        var context = objectMapper.readTree(jdbcTemplate.queryForObject("select execution_context_json from research_agent_task where research_run_id = ?", String.class, runId));
        assertThat(context.path("query_policy").path("discovery_input").path("table").path("entities")).hasSize(1);
        assertThat(context.path("source_policy").path("source_scope").get(0).path("source_snapshot_id").asText()).isNotBlank();
        settleDiscoveryRound();
        assertThat(discoveryLoop.advance(runId, 2).outcome()).isEqualTo("POPULATE_CELLS");
        jdbcTemplate.update("update research_agent_task set status = 'SUBMITTED' where research_run_id = ?", runId);
        assertThat(discoveryLoop.advance(runId, 3).outcome()).isEqualTo("EXPAND_ROWS");
        settleDiscoveryRound();
        // 重新创建服务，状态完全从数据库恢复。
        var restarted = new ResearchDiscoveryLoopService(jdbcTemplate, objectMapper, featureFlags, coordinator);
        restarted.setWideDiscoveryEffortTiers("ANY");
        assertThat(restarted.advance(runId, 4).outcome()).isEqualTo("STOPPED");
        assertThat(jdbcTemplate.queryForObject("select stop_reason from research_discovery_loop where research_run_id = ?", String.class, runId)).isEqualTo("DISCOVERY_SATURATED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_discovery_round where research_run_id = ?", Integer.class, runId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ? and role = 'DEEP_CELL'", Integer.class, runId)).isEqualTo(2);
    }

    @Test
    void discoveryLoopShouldCountActiveReservationsBeforeDispatchingCells() {
        jdbcTemplate.update("update research_run set agent_feature_flags_json = ? where id = ?", "{\"wide_discovery_v1\":true}", runId);
        discoveryLoop.advance(runId, 1);
        settleDiscoveryRound();
        jdbcTemplate.update("update research_discovery_loop set limits_json = ? where research_run_id = ?", "{\"llm_calls\":20,\"search_calls\":12}", runId);
        assertThat(discoveryLoop.advance(runId, 2).outcome()).isEqualTo("STOPPED");
        assertThat(jdbcTemplate.queryForObject("select stop_reason from research_discovery_loop where research_run_id = ?", String.class, runId)).isEqualTo("LOOP_BUDGET_EXHAUSTED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void releasedReservationsShouldAllowRemainingCellsWithoutRepeatingCompletedTargets() {
        jdbcTemplate.update("update research_run set agent_feature_flags_json = ? where id = ?", "{\"wide_discovery_v1\":true}", runId);
        discoveryLoop.advance(runId, 1);
        settleDiscoveryRound();
        jdbcTemplate.update("update research_discovery_loop set limits_json = ? where research_run_id = ?", "{\"llm_calls\":70,\"search_calls\":256}", runId);
        assertThat(discoveryLoop.advance(runId, 2).taskization().createdTaskCount()).isEqualTo(1);
        jdbcTemplate.update("update research_agent_task set status = 'SUBMITTED' where research_run_id = ? and role = 'DEEP_CELL'", runId);
        jdbcTemplate.update("update research_budget_reservation set state = 'SETTLED', consumed_json = '{}' where research_agent_task_id in (select id from research_agent_task where research_run_id = ? and role = 'DEEP_CELL')", runId);
        assertThat(discoveryLoop.advance(runId, 3).taskization().createdTaskCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ? and role = 'DEEP_CELL'", Integer.class, runId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select stop_reason from research_discovery_loop where research_run_id = ?", String.class, runId)).isNull();
    }

    @Test
    void continuouslyNovelDiscoveryShouldStopAtThreeRounds() {
        jdbcTemplate.update("update research_run set agent_feature_flags_json = ? where id = ?", "{\"wide_discovery_v1\":true}", runId);
        jdbcTemplate.update("update research_cell set cell_status = 'FROZEN' where research_run_id = ?", runId);
        for (int round = 1; round <= 3; round++) {
            assertThat(discoveryLoop.advance(runId, round).outcome()).isEqualTo("EXPAND_ROWS");
            settleDiscoveryRound();
            jdbcTemplate.update("update research_discovery_round set new_proposal_count = 1 where research_run_id = ?", runId);
        }
        assertThat(discoveryLoop.advance(runId, 4).outcome()).isEqualTo("STOPPED");
        assertThat(jdbcTemplate.queryForObject("select stop_reason from research_discovery_loop where research_run_id = ?", String.class, runId)).isEqualTo("DISCOVERY_ROUND_LIMIT");
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ?", Integer.class, runId)).isEqualTo(3);
    }

    private void settleDiscoveryRound() {
        jdbcTemplate.update("update research_agent_task set status = 'SUBMITTED' where research_run_id = ? and role = 'WIDE_DISCOVERY'", runId);
        jdbcTemplate.update("update research_discovery_round set round_status = 'COMPLETED', new_proposal_count = 0 where research_run_id = ?", runId);
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
