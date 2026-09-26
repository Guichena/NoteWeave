package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.storage.ObjectStorage;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M4-A (D-37): Java-side hydration correctness that is independent of which research link is the
 * supported one. Every case here is a silent-degradation guard.
 */
@SpringBootTest(properties = {
        "noteweave.research.checkpoint-hydration-v2=true"
})
@ActiveProfiles("test")
class ResearchAgentCheckpointHydrationCorrectnessTest {

    private static final String LEDGER_DIGEST = "sha256:" + "3".repeat(64);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchAgentCheckpointHydrator hydrator;
    @Autowired private ResearchAgentCheckpointSnapshotCompiler snapshotCompiler;
    @Autowired private ResearchAgentCompletionCanonicalizer canonicalizer;
    @Autowired private ResearchAgentCoordinatorSnapshotService snapshots;
    @Autowired private ResearchRunCommandService commandService;
    @Autowired private ObjectStorage storage;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Replay guard. research_verifier_decision has only a plain index, so before M4-A a second
     * hydration silently duplicated decision rows. The replay must now be rejected by the explicit
     * (source run, source checkpoint) key and leave no row behind anywhere.
     */
    @Test
    void replayingTheSameCheckpointIsRejectedWithZeroNewRows() {
        Fixture fixture = seedHydratableRun(true, false);
        String firstDescendant = seedRun(fixture.workspaceId(), true);
        transactions.executeWithoutResult(status ->
                hydrator.hydrate(fixture.workspaceId(), fixture.runId(), 1, firstDescendant));
        assertThat(decisions(firstDescendant))
                .as("the restored decision is copied although the table has no unique key")
                .isEqualTo(1);

        int decisionsBefore = countAllDecisions();
        int cellsBefore = countAllCells();
        String secondDescendant = seedRun(fixture.workspaceId(), true);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                hydrator.hydrate(fixture.workspaceId(), fixture.runId(), 1, secondDescendant)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESEARCH_CHECKPOINT_HYDRATION_REPLAY"));

        assertThat(countAllDecisions()).isEqualTo(decisionsBefore);
        assertThat(countAllCells()).isEqualTo(cellsBefore);
        assertThat(decisions(secondDescendant)).isZero();
        assertThat(cells(secondDescendant)).isZero();
        // The rejected replay must not have left a partial idempotency claim either.
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_run
                where id = ? and hydrated_from_research_run_id is null and hydrated_from_checkpoint_seq is null
                """, Integer.class, secondDescendant)).isEqualTo(1);
    }

    /**
     * An {@code AUTO} request that cannot hydrate must be persisted as an observable degradation,
     * and must be distinguishable from an explicit {@code CONTEXT_RESTART}.
     */
    @Test
    void autoResumeWithoutHydrationPersistsAnExplicitDegradationReason() {
        String workspaceId = createWorkspace("hydration-auto-degrade");
        ResearchRunResponse sourceRun = commandService.createRun(workspaceId, request());
        restoreFlagSnapshotAsJsonObject(sourceRun.researchRunId());
        insertLegacyExecutionCheckpoint(workspaceId, sourceRun.researchRunId());

        ResearchRunResponse auto = commandService.resumeFromCheckpoint(
                workspaceId, sourceRun.researchRunId(), 1);
        assertThat(auto.resumeMode()).isEqualTo("CONTEXT_RESTART");
        assertThat(auto.resumeModeReason()).isEqualTo("AUTO_HYDRATION_UNAVAILABLE");
        assertThat(persistedMode(auto.researchRunId())).isEqualTo("CONTEXT_RESTART");
        assertThat(persistedModeReason(auto.researchRunId())).isEqualTo("AUTO_HYDRATION_UNAVAILABLE");

        ResearchRunResponse explicit = commandService.resumeFromCheckpoint(
                workspaceId, sourceRun.researchRunId(), 1, "CONTEXT_RESTART");
        assertThat(explicit.resumeMode()).isEqualTo("CONTEXT_RESTART");
        assertThat(explicit.resumeModeReason()).isEqualTo("EXPLICIT_CONTEXT_RESTART");
        assertThat(persistedModeReason(explicit.researchRunId())).isEqualTo("EXPLICIT_CONTEXT_RESTART");

        assertThat(auto.resumeModeReason())
                .as("AUTO degradation and explicit restart must not be indistinguishable")
                .isNotEqualTo(explicit.resumeModeReason());
    }

    /**
     * hydrate() must fail closed on its own authorization even when a caller bypasses
     * {@link ResearchAgentCheckpointHydrator#available(String, String, int)}.
     */
    @Test
    void hydrateFailsClosedWhenTheRunHasNoHydrationAuthorization() {
        Fixture fixture = seedHydratableRun(false, false);
        String descendant = seedRun(fixture.workspaceId(), true);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                hydrator.hydrate(fixture.workspaceId(), fixture.runId(), 1, descendant)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESEARCH_CHECKPOINT_HYDRATION_DISABLED"));

        assertThat(cells(descendant)).isZero();
        assertThat(decisions(descendant)).isZero();
    }

    /**
     * Chosen semantics (a): a fully VERIFIED canonical ledger is complete work, so a hydrated Run
     * is ready for finalization immediately - but the restoration must be recorded on the Run and
     * in its genesis checkpoint so "restored then finished" never reads like "this Run did it".
     */
    @Test
    void hydratingAFullyVerifiedLedgerIsReadyForFinalizationAndCarriesItsProvenance() {
        Fixture fixture = seedHydratableRun(true, true);
        String descendant = seedRun(fixture.workspaceId(), true);
        transactions.executeWithoutResult(status ->
                hydrator.hydrate(fixture.workspaceId(), fixture.runId(), 1, descendant));

        ResearchAgentCoordinatorSnapshotService.Snapshot snapshot = snapshots.snapshot(descendant);
        assertThat(snapshot.verifiedCellCount()).isEqualTo(1);
        assertThat(snapshot.nonFinalizableCellCount()).isZero();
        assertThat(snapshot.taskCount())
                .as("the descendant executed no work of its own")
                .isZero();
        assertThat(snapshot.readyForFinalization()).isTrue();

        assertThat(jdbcTemplate.queryForObject(
                "select hydrated_from_research_run_id from research_run where id = ?",
                String.class, descendant)).isEqualTo(fixture.runId());
        assertThat(jdbcTemplate.queryForObject(
                "select hydrated_from_checkpoint_seq from research_run where id = ?",
                Integer.class, descendant)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select hydrated_source_ledger_digest from research_run where id = ?",
                String.class, descendant)).isEqualTo(LEDGER_DIGEST);
        String genesisSummary = jdbcTemplate.queryForObject("""
                select summary_json from research_agent_checkpoint
                where research_run_id = ? and checkpoint_seq = 1
                """, String.class, descendant);
        assertThat(genesisSummary)
                .contains("HYDRATED_LEDGER_RESUME")
                .contains(fixture.runId());
    }

    /**
     * A missing plan_revision / entity_set_version must fail explicitly. Defaulting to 0 produces a
     * Cell whose CAS identity can never equal a task, freezing the Run without an error.
     */
    @Test
    void hydrationFailsExplicitlyWhenThePlanRevisionIsMissing() {
        Fixture fixture = seedHydratableRun(true, false);
        String checkpointId = insertCheckpoint(fixture.runId(), 2);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", "research-agent-checkpoint-hydration.v1");
        payload.put("source_research_run_id", fixture.runId());
        payload.put("source_checkpoint_id", checkpointId);
        payload.put("checkpoint_seq", 2);
        payload.put("wave_no", 1);
        payload.put("ledger_digest", LEDGER_DIGEST);
        payload.put("branches", List.of());
        payload.put("rows", List.of());
        payload.put("cells", List.of());
        payload.put("accepted_evidence", List.of());
        payload.put("open_decisions", List.of());
        payload.put("stages", List.of());
        payload.put("budget_summary", Map.of());
        // plan_revision / entity_set_version are intentionally absent.
        insertSnapshot(fixture.runId(), checkpointId, 2, payload);

        String descendant = seedRun(fixture.workspaceId(), true);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                hydrator.hydrate(fixture.workspaceId(), fixture.runId(), 2, descendant)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESEARCH_CHECKPOINT_HYDRATION_IDENTITY_MISSING"));
    }

    /**
     * The copied stage barrier must not be the dead, illegal 'HYDRATED' status pointing at the
     * source Run; it is recomputed for the descendant with a status the refill service owns.
     */
    @Test
    void hydratedStageBarrierIsRecomputedForTheDescendantWithALegalStatus() {
        Fixture fixture = seedHydratableRun(true, false);
        String descendant = seedRun(fixture.workspaceId(), true);
        transactions.executeWithoutResult(status ->
                hydrator.hydrate(fixture.workspaceId(), fixture.runId(), 1, descendant));

        String stageStatus = jdbcTemplate.queryForObject("""
                select status from research_run_stage where research_run_id = ?
                """, String.class, descendant);
        assertThat(stageStatus).isIn("ACTIVE", "BARRIER_PENDING", "SETTLED");

        String barrierJson = jdbcTemplate.query("""
                select barrier_json from research_run_stage where research_run_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, descendant);
        assertThat(barrierJson)
                .contains(descendant)
                .doesNotContain(fixture.runId());
    }

    // ---------------------------------------------------------------------------------------------

    private String createWorkspace(String name) {
        String workspaceId = Ids.newId();
        jdbcTemplate.update("""
                insert into workspace(id, owner_id, name, status)
                values (?, 'local-user', ?, 'ACTIVE')
                """, workspaceId, name);
        return workspaceId;
    }

    private String seedRun(String workspaceId, boolean hydrationEnabled) {
        String taskId = Ids.newId();
        String runId = Ids.newId();
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, taskId, workspaceId, runId);
        jdbcTemplate.update("""
                insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json,
                    status, agent_execution_mode, agent_feature_flags_json)
                values (?, ?, ?, 'm4a hydration question', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1',
                    json_object('checkpoint_hydration_v2', ?))
                """, runId, workspaceId, taskId, hydrationEnabled);
        return runId;
    }

    private Fixture seedHydratableRun(boolean hydrationFlag, boolean verifiedLedger) {
        String workspaceId = createWorkspace("m4a-" + Ids.newId());
        String runId = seedRun(workspaceId, hydrationFlag);

        String branchId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_branch(id, research_run_id, branch_key, branch_reason, branch_status, created_round)
                values (?, ?, 'branch-main', 'M4A', 'ACTIVE', 1)
                """, branchId, runId);
        String rowId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_row(id, research_run_id, row_key, branch_id, source_title, row_status, verification_status)
                values (?, ?, 'subject', ?, 'Subject', 'CANDIDATE_READY', 'PENDING')
                """, rowId, runId, branchId);

        String cellId = Ids.newId();
        if (verifiedLedger) {
            String evidenceId = Ids.newId();
            jdbcTemplate.update("""
                    insert into source_evidence(id, research_run_id, evidence_key, window_id, source_id,
                        source_title, relation_type, snapshot_status)
                    values (?, ?, 'ev-1', 'w-1', 'src-1', 'Source', 'SUPPORTS', 'WORKSPACE')
                    """, evidenceId, runId);
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
        } else {
            jdbcTemplate.update("""
                    insert into research_cell(id, research_run_id, research_row_id, cell_key, branch_id, column_key,
                        cell_status, evidence_refs_json, repair_count, cell_version, plan_revision, entity_set_version)
                    values (?, ?, ?, 'subject:answer', ?, 'answer', 'GAP', '[]', 0, 0, 0, 0)
                    """, cellId, runId, rowId, branchId);
            jdbcTemplate.update("""
                    insert into research_verifier_decision(id, research_run_id, decision_scope, decision_type,
                        reason_code, target_id, evidence_ids_json, action_text, decision_status, notes_json)
                    values (?, ?, 'CELL', 'QUORUM_REPAIR_REQUIRED', 'M4A_FIXTURE', 'subject:answer',
                        '[]', 'COUNTERFACTUAL_REPAIR', 'OPEN', '[]')
                    """, Ids.newId(), runId);
        }

        jdbcTemplate.update("""
                insert into research_run_stage(id, research_run_id, stage, stage_revision, status, barrier_digest,
                    expected_task_count, settled_task_count, blocker_count, stage_version, barrier_json)
                values (?, ?, 'CELL_RESEARCH', 1, 'ACTIVE', ?, 1, 0, 0, 'research-run-stage.v1',
                    json_object('stage', 'CELL_RESEARCH'))
                """, Ids.newId(), runId, "sha256:" + "9".repeat(64));

        String checkpointId = insertCheckpoint(runId, 1);
        snapshotCompiler.compile(checkpointId, 1, new ResearchBudgetAndCheckpointService.CheckpointCommand(
                runId, 1, 1, 0, 0, LEDGER_DIGEST, 0L, 0L, 0L, Map.of(), Map.of("genesis", true)));
        return new Fixture(workspaceId, runId);
    }

    private String insertCheckpoint(String runId, int checkpointSeq) {
        String checkpointId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_agent_checkpoint(
                    id, research_run_id, checkpoint_seq, wave_no, round_no, plan_revision, entity_set_version,
                    ledger_hash, task_high_water_mark, candidate_high_water_mark, merge_high_water_mark,
                    budget_summary_json, summary_json)
                values (?, ?, ?, 1, 1, 0, 0, ?, 0, 0, 0, '{}', '{}')
                """, checkpointId, runId, checkpointSeq, LEDGER_DIGEST);
        return checkpointId;
    }

    private void insertSnapshot(String runId, String checkpointId, int checkpointSeq, Map<String, Object> payload) {
        String canonicalJson = canonicalizer.canonicalJsonValue(payload);
        byte[] bytes = canonicalJson.getBytes(StandardCharsets.UTF_8);
        jdbcTemplate.update("""
                insert into research_checkpoint_hydration_snapshot(
                    id, research_run_id, checkpoint_id, checkpoint_seq, schema_version,
                    payload_json, content_size, payload_sha256, canonical_digest, ledger_digest)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Ids.newId(), runId, checkpointId, checkpointSeq,
                ResearchAgentCheckpointSnapshotCompiler.SCHEMA_VERSION, canonicalJson, bytes.length,
                ResearchCheckpointIntegrity.sha256(bytes),
                canonicalizer.domainSeparatedDigest("research-agent-checkpoint-hydration.v1", payload),
                LEDGER_DIGEST);
    }

    /**
     * H2's MySQL mode stores a bound VARCHAR into a {@code json} column as a JSON string scalar, so
     * a Run created through {@code createRun} cannot be re-read as a flags object. Rewrite the
     * snapshot as a real JSON object before {@code enabledForRun} parses it.
     */
    private void restoreFlagSnapshotAsJsonObject(String runId) {
        jdbcTemplate.update("""
                update research_run
                set agent_feature_flags_json = json_object('checkpoint_hydration_v2', true)
                where id = ?
                """, runId);
    }

    private void insertLegacyExecutionCheckpoint(String workspaceId, String runId) {
        String objectKey = "workspace/%s/research/%s/checkpoints/1.json".formatted(workspaceId, runId);
        byte[] payload = "{\"checkpoint_no\":1}".getBytes(StandardCharsets.UTF_8);
        storage.write("noteweave-derived", objectKey, payload);
        jdbcTemplate.update("""
                insert into research_execution_checkpoint(
                    id, research_run_id, checkpoint_no, snapshot_type, object_key,
                    payload_sha256, content_size, active_branch_key, final_loop_decision, summary_json)
                values (?, ?, 1, 'LOOP_END', ?, ?, ?, 'branch-main', 'CONTINUE', '{}')
                """, Ids.newId(), runId, objectKey,
                ResearchCheckpointIntegrity.sha256(payload), payload.length);
    }

    private String persistedMode(String runId) {
        return jdbcTemplate.queryForObject(
                "select resume_mode from research_run where id = ?", String.class, runId);
    }

    private String persistedModeReason(String runId) {
        return jdbcTemplate.queryForObject(
                "select resume_mode_reason from research_run where id = ?", String.class, runId);
    }

    private int decisions(String runId) {
        return count("select count(*) from research_verifier_decision where research_run_id = ?", runId);
    }

    private int cells(String runId) {
        return count("select count(*) from research_cell where research_run_id = ?", runId);
    }

    private int countAllDecisions() {
        return count("select count(*) from research_verifier_decision", null);
    }

    private int countAllCells() {
        return count("select count(*) from research_cell", null);
    }

    private int count(String sql, String runId) {
        Integer value = runId == null
                ? jdbcTemplate.queryForObject(sql, Integer.class)
                : jdbcTemplate.queryForObject(sql, Integer.class, runId);
        return value == null ? 0 : value;
    }

    private CreateResearchRunRequest request() {
        return new CreateResearchRunRequest(
                "How is hydration correctness isolated from the link decision?",
                "DEFAULT",
                "Verify hydration guards",
                "Markdown report",
                List.of("Preserve checkpoint lineage"),
                null,
                "STANDARD",
                "TECHNICAL",
                List.of(),
                "WEB_ONLY",
                List.of()
        );
    }

    private record Fixture(String workspaceId, String runId) { }
}
