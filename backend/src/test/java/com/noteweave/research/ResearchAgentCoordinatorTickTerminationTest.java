package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * M4-A2: a Run with {@code taskCount == 0} whose remaining Cells cannot be scheduled must still
 * reach a terminal state. Before this the coordinator tick's initial-wave branch returned
 * {@code INITIAL_WAVE_TASKIZED} for every zero-task Run, which shadowed the terminal decision and
 * spun forever for a hydrated ledger and for an all-conflict ledger.
 */
@SpringBootTest(properties = {
        "noteweave.research.checkpoint-hydration-v2=true"
})
@ActiveProfiles("test")
class ResearchAgentCoordinatorTickTerminationTest {

    private static final String LEDGER_DIGEST = "sha256:" + "3".repeat(64);
    private static final String SOURCE_SCOPE_EMPTY = "[]";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ResearchAgentCheckpointHydrator hydrator;
    @Autowired private ResearchAgentCheckpointSnapshotCompiler snapshotCompiler;
    @Autowired private ResearchAgentCoordinatorTickService coordinatorTick;
    @Autowired private ResearchAgentTaskCoordinatorService taskCoordinator;

    /**
     * 1. A hydrated descendant Run (every Cell VERIFIED, {@code taskCount == 0}) must finalize on
     * its first tick instead of looping in {@code INITIAL_WAVE_TASKIZED}.
     */
    @Test
    void hydratedFullyVerifiedRunReachesTerminalStateInsteadOfLooping() {
        String workspaceId = createWorkspace("m4a2-hydrated");
        String sourceRunId = seedVerifiedLedger(workspaceId);
        String descendant = seedRun(workspaceId, SOURCE_SCOPE_EMPTY, "WEB_ONLY", true);
        hydrator.hydrate(workspaceId, sourceRunId, 1, descendant);
        assertThat(taskCount(descendant)).isZero();

        ResearchAgentCoordinatorTickService.TickReceipt receipt =
                coordinatorTick.tick(descendant, "m4a2-hydrated-scheduler");

        assertThat(receipt.outcome()).isEqualTo("RUN_FINALIZED");
        assertThat(runStatus(descendant)).isEqualTo("COMPLETED");
        assertThat(completionTerminalState(descendant)).isEqualTo("COMPLETED_VERIFIED");
    }

    /**
     * 2. A zero-task Run whose Cells are all a spent conflict must reach the gate's honest terminal
     * state. {@code taskCount == 0} holds here because the exhausted conflict has no OPEN trace, so
     * neither the bootstrap nor the conflict-repair advance can create a task.
     */
    @Test
    void zeroTaskRunWithExhaustedConflictsReachesTheGateTerminalState() {
        String workspaceId = createWorkspace("m4a2-conflict-exhausted");
        String sourceId = seedReadySource(workspaceId);
        String runId = seedRun(workspaceId, objectMapperJson(List.of(sourceId)), "WEB_PLUS_SEEDS", true);
        String rowId = seedRow(runId, "entity-1");
        seedCell(runId, rowId, "entity-1:answer", "answer", "CONFLICT_EXHAUSTED");
        bindEvidence(runId, "entity-1:answer", "ev-a", "alpha.com", "a".repeat(64), "revenue was 100");
        bindEvidence(runId, "entity-1:answer", "ev-b", "beta.org", "b".repeat(64), "revenue was 200");
        // The conflict was already bounded-repaired and explicitly exhausted: an EXHAUSTED trace (not
        // OPEN), so adjudicateRun preserves CONFLICT_EXHAUSTED and advance has nothing to dispatch.
        jdbcTemplate.update("""
                insert into research_verifier_decision(
                    id, research_run_id, decision_scope, decision_type, reason_code,
                    target_id, evidence_ids_json, action_text, decision_status, notes_json)
                values (?, ?, 'CELL', 'GLOBAL_CONFLICT', 'NUMBER_VALUE_CONFLICT', 'entity-1:answer',
                    '["ev-a","ev-b"]', 'counterfactual_repair_completed', 'EXHAUSTED', '{}')
                """, Ids.newId(), runId);
        assertThat(taskCount(runId)).isZero();

        ResearchAgentCoordinatorTickService.TickReceipt receipt =
                coordinatorTick.tick(runId, "m4a2-conflict-scheduler");

        assertThat(receipt.outcome()).isEqualTo("RUN_COMPLETED");
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(completionTerminalState(runId)).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(completionReasonCodes(runId)).contains("CONFLICT_REPAIR_EXHAUSTED");
        assertThat(cellStatus(runId, "entity-1:answer")).isEqualTo("CONFLICT_EXHAUSTED");
        // Still no task was invented just to make the branch look productive.
        assertThat(taskCount(runId)).isZero();
    }

    @Test
    void exhaustedVerifierRepairWithoutSafeTargetCompletesHonestlyInsteadOfFailingRun() {
        String workspaceId = createWorkspace("m4a2-verifier-repair-exhausted");
        String sourceId = seedReadySource(workspaceId);
        String runId = seedRun(workspaceId, objectMapperJson(List.of(sourceId)), "WEB_PLUS_SEEDS", true);
        String rowId = seedRow(runId, "entity-1");
        seedCell(runId, rowId, "entity-1:answer", "answer", "GAP");
        taskCoordinator.planAndEnqueueForWave(runId, 1);
        jdbcTemplate.update("""
                update research_agent_task set status = 'SUBMITTED', terminal_reason = 'CANDIDATES_PROPOSED',
                    terminal_at = current_timestamp where research_run_id = ?
                """, runId);
        jdbcTemplate.update("""
                update research_cell set cell_status = 'CONFLICT_EXHAUSTED', repair_count = 2,
                    active_task_id = null where research_run_id = ?
                """, runId);
        bindEvidence(runId, "entity-1:answer", "ev-a", "alpha.com", "a".repeat(64), "revenue was 100");
        jdbcTemplate.update("""
                insert into research_verifier_decision(
                    id, research_run_id, decision_scope, decision_type, reason_code,
                    target_id, evidence_ids_json, action_text, decision_status, notes_json)
                values (?, ?, 'CELL', 'QUORUM_REPAIR_REQUIRED', 'QUORUM_VALUE_CONFLICT',
                    'entity-1:answer', '["ev-a","ev-b"]', 'COUNTERFACTUAL_REPAIR', 'OPEN', '{}')
                """, Ids.newId(), runId);

        ResearchAgentCoordinatorTickService.TickReceipt receipt =
                coordinatorTick.tick(runId, "m4a2-verifier-exhausted-scheduler");

        assertThat(receipt.outcome()).isEqualTo("VERIFIER_REPAIR_STOPPED_COMPLETED");
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(completionTerminalState(runId)).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(completionReasonCodes(runId)).contains("INSUFFICIENT_EVIDENCE");
    }

    /**
     * 3. A genuinely new Run (only GAP Cells) must keep bootstrapping its initial wave.
     */
    @Test
    void brandNewRunWithGapCellsStillBootstrapsItsInitialWave() {
        String workspaceId = createWorkspace("m4a2-new-run");
        String sourceId = seedReadySource(workspaceId);
        String runId = seedRun(workspaceId, objectMapperJson(List.of(sourceId)), "WEB_PLUS_SEEDS", true);
        String rowId = seedRow(runId, "entity-1");
        seedCell(runId, rowId, "entity-1:answer", "answer", "GAP");

        ResearchAgentCoordinatorTickService.TickReceipt receipt =
                coordinatorTick.tick(runId, "m4a2-new-run-scheduler");

        assertThat(receipt.outcome()).isEqualTo("INITIAL_WAVE_TASKIZED");
        assertThat(receipt.taskization().createdTaskCount()).isGreaterThan(0);
        assertThat(taskCount(runId)).isGreaterThan(0);
        assertThat(runStatus(runId)).isEqualTo("RUNNING");
    }

    // ---------------------------------------------------------------------------------------------

    private String createWorkspace(String name) {
        String workspaceId = Ids.newId();
        jdbcTemplate.update("""
                insert into workspace(id, owner_id, name, status)
                values (?, 'local-user', ?, 'ACTIVE')
                """, workspaceId, name + "-" + workspaceId);
        return workspaceId;
    }

    private String seedReadySource(String workspaceId) {
        String fileId = Ids.newId();
        String sourceId = Ids.newId();
        String snapshotId = Ids.newId();
        String chunkId = Ids.newId();
        jdbcTemplate.update(
                "insert into file_object(id, workspace_id, object_key, sha256, file_size) values (?, ?, ?, ?, 20)",
                fileId, workspaceId, "source/" + sourceId + ".txt", "d".repeat(64));
        jdbcTemplate.update("""
                insert into source(id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status)
                values (?, ?, ?, 'Trusted source', 'TEXT', 'READY', 'READY', 'READY')
                """, sourceId, workspaceId, fileId);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status)
                values (?, ?, ?, 1, ?, ?, 'READY', 'READY')
                """, snapshotId, sourceId, fileId, "source/" + sourceId + ".txt", "e".repeat(64));
        jdbcTemplate.update("""
                insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no, content, token_estimate)
                values (?, ?, ?, ?, 1, 'trusted content', 5)
                """, chunkId, workspaceId, sourceId, snapshotId);
        jdbcTemplate.update("insert into source_window(id, source_chunk_id, window_no, content) values (?, ?, 1, 'trusted content')",
                Ids.newId(), chunkId);
        return sourceId;
    }

    private String seedRun(String workspaceId, String scopeJson, String retrievalMode, boolean hydrationEnabled) {
        String taskId = Ids.newId();
        String runId = Ids.newId();
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, taskId, workspaceId, runId);
        jdbcTemplate.update("""
                insert into research_run(
                    id, workspace_id, task_id, question, profile_key, source_scope_json,
                    research_intent_json, control_pack_json, retrieval_mode, status, agent_execution_mode,
                    agent_feature_flags_json)
                values (?, ?, ?, 'm4a2 termination question', 'DEFAULT', ?, ?, ?, ?, 'RUNNING',
                    'INCREMENTAL_V1', json_object('checkpoint_hydration_v2', ?))
                """, runId, workspaceId, taskId, scopeJson,
                objectMapperJson(Map.of("research_goal", "m4a2", "deliverable_format", "memo",
                        "constraints", List.of("auditable"), "time_range", "2024-2026", "depth", "DEEP",
                        "research_type", "TECHNICAL")),
                objectMapperJson(Map.of("pack_type", "RESEARCH_AGENT", "target_key", "DEFAULT",
                        "task_neighborhood", "RESEARCH_DEFAULT", "style_constraints", List.of("concise"),
                        "structure_constraints", List.of("limitations"), "terminology_policy", List.of("canonical"),
                        "forbidden_patterns", List.of("unsupported"), "evidence_policy", List.of("snapshot-only"))),
                retrievalMode, hydrationEnabled);
        return runId;
    }

    private String seedRow(String runId, String rowKey) {
        String rowId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_row(id, research_run_id, row_key, row_status)
                values (?, ?, ?, 'CANDIDATE_READY')
                """, rowId, runId, rowKey);
        return rowId;
    }

    private void seedCell(String runId, String rowId, String cellKey, String columnKey, String status) {
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key,
                    candidate_value, cell_status, evidence_refs_json, repair_count, cell_version,
                    plan_revision, entity_set_version)
                values (?, ?, ?, ?, ?, '', ?, '[]', 0, 0, 1, 1)
                """, Ids.newId(), runId, rowId, cellKey, columnKey, status);
    }

    private void bindEvidence(String runId, String cellKey, String evidenceKey, String domain,
                              String lineage, String claim) {
        String evidenceId = Ids.newId();
        jdbcTemplate.update("""
                insert into source_evidence(id, research_run_id, evidence_key, source_id, relation_type,
                    claim_text, quote_text, source_domain, lineage_digest, snapshot_status)
                values (?, ?, ?, ?, 'SUPPORTS', ?, ?, ?, ?, 'WORKSPACE')
                """, evidenceId, runId, evidenceKey, "evidence-source-" + evidenceKey,
                claim, claim, domain, lineage);
        jdbcTemplate.update("""
                insert into research_cell_evidence(id, research_run_id, research_cell_id, source_evidence_id, evidence_key)
                values (?, ?, (select id from research_cell where research_run_id = ? and cell_key = ?), ?, ?)
                """, Ids.newId(), runId, runId, cellKey, evidenceId, evidenceKey);
    }

    /** Source Run whose canonical ledger is one fully VERIFIED Cell with one evidence reference. */
    private String seedVerifiedLedger(String workspaceId) {
        String runId = seedRun(workspaceId, SOURCE_SCOPE_EMPTY, "WEB_ONLY", true);
        String branchId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_branch(id, research_run_id, branch_key, branch_reason, branch_status, created_round)
                values (?, ?, 'branch-main', 'M4A2', 'ACTIVE', 1)
                """, branchId, runId);
        String rowId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_row(id, research_run_id, row_key, branch_id, source_title, row_status, verification_status)
                values (?, ?, 'subject', ?, 'Subject', 'CANDIDATE_READY', 'PENDING')
                """, rowId, runId, branchId);
        String evidenceId = Ids.newId();
        jdbcTemplate.update("""
                insert into source_evidence(id, research_run_id, evidence_key, window_id, source_id,
                    source_title, relation_type, snapshot_status)
                values (?, ?, 'ev-1', 'w-1', 'src-1', 'Source', 'SUPPORTS', 'WORKSPACE')
                """, evidenceId, runId);
        String cellId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, branch_id, column_key,
                    candidate_value, cell_status, evidence_refs_json, repair_count, cell_version,
                    plan_revision, entity_set_version)
                values (?, ?, ?, 'subject:answer', ?, 'answer', 'Verified answer', 'VERIFIED', '["ev-1"]',
                    0, 1, 0, 0)
                """, cellId, runId, rowId, branchId);
        jdbcTemplate.update("""
                insert into research_cell_evidence(id, research_run_id, research_cell_id, source_evidence_id, evidence_key)
                values (?, ?, ?, ?, 'ev-1')
                """, Ids.newId(), runId, cellId, evidenceId);

        String checkpointId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_agent_checkpoint(
                    id, research_run_id, checkpoint_seq, wave_no, round_no, plan_revision, entity_set_version,
                    ledger_hash, task_high_water_mark, candidate_high_water_mark, merge_high_water_mark,
                    budget_summary_json, summary_json)
                values (?, ?, 1, 1, 1, 0, 0, ?, 0, 0, 0, '{}', '{}')
                """, checkpointId, runId, LEDGER_DIGEST);
        snapshotCompiler.compile(checkpointId, 1, new ResearchBudgetAndCheckpointService.CheckpointCommand(
                runId, 1, 1, 0, 0, LEDGER_DIGEST, 0L, 0L, 0L, Map.of(), Map.of("genesis", true)));
        return runId;
    }

    private String objectMapperJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize fixture JSON", exception);
        }
    }

    private int taskCount(String runId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from research_agent_task where research_run_id = ?", Integer.class, runId);
        return count == null ? 0 : count;
    }

    private String runStatus(String runId) {
        return jdbcTemplate.queryForObject("select status from research_run where id = ?", String.class, runId);
    }

    private String completionTerminalState(String runId) {
        return jdbcTemplate.queryForObject(
                "select completion_terminal_state from research_run where id = ?", String.class, runId);
    }

    private String completionReasonCodes(String runId) {
        return jdbcTemplate.queryForObject(
                "select completion_reason_codes_json from research_run where id = ?", String.class, runId);
    }

    private String cellStatus(String runId, String cellKey) {
        return jdbcTemplate.queryForObject(
                "select cell_status from research_cell where research_run_id = ? and cell_key = ?",
                String.class, runId, cellKey);
    }
}
