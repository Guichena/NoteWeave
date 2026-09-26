package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
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
 * DR-110 trigger wiring: the Replan audit is written by the Discovery revision itself, not by a
 * direct {@code recordReplan} call. These tests drive the real trigger -
 * {@code ResearchDiscoveryProposalService.validateAndPersist} and
 * {@code ResearchAgentCheckpointHydrator.hydrate} - and read the resulting rows back from the
 * database.
 */
@SpringBootTest(properties = {
        "noteweave.research.wide-discovery-auto-accept=true",
        "noteweave.research.wide-discovery-accepted-precision=1.0",
        "noteweave.research.wide-discovery-precision-threshold=0.90",
        // hydrate() is now fail-closed on its own feature flag (M4-A): the direct hydrator call
        // below must carry the same authorization the resume entry point would have checked.
        "noteweave.research.checkpoint-hydration-v2=true"
})
@ActiveProfiles("test")
class ResearchAgentReplanAuditDiscoveryWiringTest {

    private static final String INPUT_DIGEST = "sha256:" + "1".repeat(64);
    private static final String INITIAL_PLAN_DIGEST = "sha256:" + "2".repeat(64);
    private static final String LEDGER_DIGEST = "sha256:" + "3".repeat(64);
    private static final String EXECUTION_CONTEXT = """
            {"query_policy":{"discovery_input":{"input_digest":"%s","allowed_source_ids":[]}}}
            """.formatted(INPUT_DIGEST);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchDiscoveryProposalService discoveryProposals;
    @Autowired private ResearchAgentReplanAuditService replanAudit;
    @Autowired private ResearchAgentCheckpointSnapshotCompiler snapshotCompiler;
    @Autowired private ResearchAgentCheckpointHydrator hydrator;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void acceptedDiscoveryRevisionShouldPersistOneScopeExpandedReplanAuditRow() {
        Fixture fixture = seedDiscoveryRun();

        submitDiscovery(fixture, "1");

        List<ResearchAgentReplanAuditService.ReplanAudit> audits = replanAudit.findByRun(fixture.runId());
        assertThat(audits).hasSize(1);
        ResearchAgentReplanAuditService.ReplanAudit audit = audits.get(0);

        assertThat(audit.deviationType()).isEqualTo(ResearchReplanDeviationTypes.SCOPE_EXPANDED);
        assertThat(audit.planRevisionFrom()).isZero();
        assertThat(audit.planRevisionTo()).isEqualTo(1);
        assertThat(audit.oldPlanDigest()).isEqualTo(INITIAL_PLAN_DIGEST);
        assertThat(audit.newPlanDigest()).isNotEqualTo(audit.oldPlanDigest());
        assertThat(audit.observedFacts()).isNotBlank().hasSizeLessThanOrEqualTo(512).doesNotContain("\n");
        assertThat(audit.selectedRepair()).isNotBlank().hasSizeLessThanOrEqualTo(512);
        assertThat(audit.budgetDelta()).isEmpty();

        // The affected cells are the cells the revision inserted, not a placeholder list.
        assertThat(audit.affectedCells()).containsExactlyInAnyOrder(
                "entity-1:answer", "entity-1:implications", "entity-1:key_evidence",
                "entity-1:limitations", "subject:dimension-1");
        for (String cellKey : audit.affectedCells()) {
            assertThat(jdbcTemplate.queryForObject("""
                    select count(*) from research_cell
                    where research_run_id = ? and cell_key = ? and plan_revision = ?
                    """, Integer.class, fixture.runId(), cellKey, audit.planRevisionTo()))
                    .as("affected cell %s must exist at the revision the audit names", cellKey)
                    .isEqualTo(1);
        }

        // plan_revision_to is the revision of the plan row the same transaction wrote.
        assertThat(jdbcTemplate.queryForObject("""
                select planner_version from research_matrix_plan
                where research_run_id = ? and plan_mode = 'DISCOVERY_REVISION' and plan_status = 'ACTIVE'
                """, String.class, fixture.runId()))
                .isEqualTo("intent-matrix.v2-revision-" + audit.planRevisionTo());
        assertThat(jdbcTemplate.queryForObject("""
                select plan_digest from research_matrix_plan
                where research_run_id = ? and plan_mode = 'DISCOVERY_REVISION' and plan_status = 'ACTIVE'
                """, String.class, fixture.runId()))
                .isEqualTo(audit.newPlanDigest());
        assertThat(planRows(fixture.runId())).isEqualTo(2);
    }

    @Test
    void replayedDiscoveryRevisionShouldBeRejectedByTheRevisionKeyWithoutASecondRow() {
        Fixture fixture = seedDiscoveryRun();
        submitDiscovery(fixture, "1");
        int planRowsBefore = planRows(fixture.runId());
        int cellsBefore = cells(fixture.runId());
        int proposalsBefore = proposalRows(fixture.runId());

        // Same run, same resulting revision, different proposal keys: the revision collides with
        // the audit row's idempotency key before it can widen the matrix a second time.
        assertThatThrownBy(() -> submitDiscovery(fixture, "2"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESEARCH_REPLAN_AUDIT_REVISION_CONFLICT"));

        assertThat(replanAudit.findByRun(fixture.runId())).hasSize(1);
        // The audit write and the plan write share one transaction: the rejected replay left
        // nothing behind.
        assertThat(planRows(fixture.runId())).isEqualTo(planRowsBefore);
        assertThat(cells(fixture.runId())).isEqualTo(cellsBefore);
        assertThat(proposalRows(fixture.runId())).isEqualTo(proposalsBefore);
        assertThat(replanAudit.findByRunAndRevision(fixture.runId(), 1)).isNotNull();
    }

    @Test
    void checkpointHydrationShouldReplayThePlanWithoutAReplanAuditRow() {
        Fixture fixture = seedDiscoveryRun();
        String checkpointId = insertCheckpoint(fixture.runId(), 1);
        // Compile before the revision so the replayed plan is the initial one; either way the
        // snapshot is taken from the same table the revision appends to.
        snapshotCompiler.compile(checkpointId, 1, new ResearchBudgetAndCheckpointService.CheckpointCommand(
                fixture.runId(), 1, 1, 0, 0, LEDGER_DIGEST, 0L, 0L, 0L, Map.of(), Map.of("genesis", true)));
        submitDiscovery(fixture, "1");

        assertThat(replanAudit.findByRun(fixture.runId())).hasSize(1);
        int auditsBefore = countAll();

        String descendantRunId = seedRun(fixture.workspaceId());
        transactions.executeWithoutResult(status ->
                hydrator.hydrate(fixture.workspaceId(), fixture.runId(), 1, descendantRunId));

        // Replaying a plan during hydration is a restore, not a decision: no audit row is written
        // for either run.
        assertThat(countAll()).isEqualTo(auditsBefore);
        assertThat(replanAudit.findByRun(fixture.runId())).hasSize(1);
        assertThat(replanAudit.findByRun(descendantRunId)).isEmpty();
        // Non-vacuous: the plan really was replayed into the descendant run.
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_matrix_plan
                where research_run_id = ? and plan_mode = 'INTENT_MATRIX_V2' and plan_status = 'HYDRATED'
                """, Integer.class, descendantRunId)).isEqualTo(1);
    }

    @Test
    void discoveryRevisionWithoutAReplacedPlanShouldFailClosedAndLeaveNothingBehind() {
        // Row and cells are present, so the audit would have affected cells; only the plan row is
        // missing. That isolates the rejection to the "no replaced plan" guard rather than letting
        // an earlier validation reject the submission first.
        Fixture fixture = seedDiscoveryRun(false, true);

        assertThatThrownBy(() -> submitDiscovery(fixture, "1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESEARCH_REPLAN_AUDIT_PLAN_MISSING"));

        // Fail closed: no plan row, no audit row and no proposal row survive the rolled-back
        // submission, so no scope change can be applied without its audit record.
        assertThat(planRows(fixture.runId())).isZero();
        assertThat(replanAudit.findByRun(fixture.runId())).isEmpty();
        assertThat(proposalRows(fixture.runId())).isZero();
    }

    @Test
    void discoveryRevisionThatExpandsNoCellShouldFailClosedAndLeaveNothingBehind() {
        // A plan row exists, so the replaced digest is resolvable; the run has no row and no cell,
        // so an accepted proposal would add nothing and the revision is not an auditable deviation.
        Fixture fixture = seedDiscoveryRun(true, false);

        assertThatThrownBy(() -> submitDiscovery(fixture, "1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESEARCH_REPLAN_AUDIT_NO_AFFECTED_CELLS"));

        // The replayed submission is rejected as a whole: the revision never appends a second plan
        // row, and neither the audit nor the proposals it wrote survive.
        assertThat(planRows(fixture.runId())).isEqualTo(1);
        assertThat(replanAudit.findByRun(fixture.runId())).isEmpty();
        assertThat(proposalRows(fixture.runId())).isZero();
    }

    private void submitDiscovery(Fixture fixture, String suffix) {
        transactions.executeWithoutResult(status -> discoveryProposals.validateAndPersist(
                fixture.task(), discoveryResult(suffix), fixture.roleResultId()));
    }

    private Map<String, Object> discoveryResult(String suffix) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result_schema_version", "research-discovery-proposal.v1");
        result.put("role", "WIDE_DISCOVERY");
        result.put("input_digest", INPUT_DIGEST);
        result.put("plan_revision", 0);
        result.put("entity_set_version", 0);
        result.put("proposals", List.of(
                proposal("proposal-entity-" + suffix, "ENTITY", "entity-" + suffix, "Discovered entity"),
                proposal("proposal-dimension-" + suffix, "DIMENSION", "dimension-" + suffix, "Discovered dimension")));
        return result;
    }

    private Map<String, Object> proposal(String proposalKey, String type, String candidateKey, String label) {
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("proposal_key", proposalKey);
        proposal.put("proposal_type", type);
        proposal.put("candidate_key", candidateKey);
        proposal.put("confidence_ppm", 900_000);
        proposal.put("label", label);
        proposal.put("search_query", label);
        proposal.put("rationale", "Discovery surfaced a gap in the frozen scope");
        proposal.put("source_domain", "");
        proposal.put("lineage_digest", "");
        proposal.put("source_lead", "");
        return proposal;
    }

    private Fixture seedDiscoveryRun() {
        return seedDiscoveryRun(true, true);
    }

    private Fixture seedDiscoveryRun(boolean includePlanRow, boolean includeMatrixCells) {
        String workspaceId = Ids.newId();
        jdbcTemplate.update(
                "insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')",
                workspaceId, "dr110-" + Ids.newId());
        String runId = seedRun(workspaceId);

        String branchId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_branch(id, research_run_id, branch_key, branch_reason, branch_status, created_round)
                values (?, ?, 'branch-main', 'DR110', 'ACTIVE', 1)
                """, branchId, runId);
        if (includeMatrixCells) {
            String rowId = Ids.newId();
            jdbcTemplate.update("""
                    insert into research_row(id, research_run_id, row_key, branch_id, source_title, row_status, verification_status)
                    values (?, ?, 'subject', ?, 'Subject', 'CANDIDATE_READY', 'PENDING')
                    """, rowId, runId, branchId);
            for (String column : List.of("answer", "key_evidence", "limitations", "implications")) {
                jdbcTemplate.update("""
                        insert into research_cell(id, research_run_id, research_row_id, cell_key, branch_id, column_key,
                            cell_status, evidence_refs_json, repair_count, cell_version, plan_revision, entity_set_version)
                        values (?, ?, ?, ?, ?, ?, 'GAP', '[]', 0, 0, 0, 0)
                        """, Ids.newId(), runId, rowId, "subject:" + column, branchId, column);
            }
        }
        if (includePlanRow) {
            jdbcTemplate.update("""
                    insert into research_matrix_plan(
                        id, research_run_id, planner_version, plan_mode, plan_status,
                        row_count, column_count, cell_count, bounded, reason_codes_json, plan_json, plan_digest)
                    values (?, ?, 'intent-matrix.v2', 'INTENT_MATRIX_V2', 'ACTIVE', 1, 4, 4, false,
                        json_array(),
                        json_object('planner_version', 'intent-matrix.v2',
                            'rows', json_array(json_object('key', 'subject', 'label', 'Subject')),
                            'columns', json_array('answer', 'key_evidence', 'limitations', 'implications'),
                            'cell_count', 4, 'bounded', false, 'reason_codes', json_array()),
                        ?)
                    """, Ids.newId(), runId, INITIAL_PLAN_DIGEST);
        }

        String taskId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_agent_task(
                    id, research_run_id, task_key, idempotency_key, wave_no, role, entity_id, branch_id,
                    plan_revision, entity_set_version, target_cells_json, budget_json, status, attempt_count)
                values (?, ?, ?, ?, 1, 'WIDE_DISCOVERY', 'subject', ?, 0, 0, '[]', '{}', 'SUBMITTED', 0)
                """, taskId, runId, "discovery-" + taskId, "idem-" + taskId, branchId);
        String executionId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_agent_execution(
                    id, research_agent_task_id, execution_key, lease_epoch, fencing_token, worker_instance_id, status)
                values (?, ?, ?, 1, 1, 'dr110-worker', 'SUBMITTED')
                """, executionId, taskId, "exec-" + executionId);
        String completionId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_agent_completion(
                    id, research_agent_task_id, execution_id, completion_key, schema_version, envelope_digest,
                    envelope_json, envelope_size_bytes, snapshot_digest, worker_instance_id, lease_epoch,
                    fencing_token, receipt_json, receipt_digest)
                values (?, ?, ?, ?, 'research-agent-completion.v2', ?, '{}', 2, ?, 'dr110-worker', 1, 1, '{}', ?)
                """, completionId, taskId, executionId, "completion-" + completionId,
                "sha256:" + "4".repeat(64), "sha256:" + "5".repeat(64), "sha256:" + "6".repeat(64));
        String roleResultId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_agent_role_result(
                    id, research_run_id, research_agent_task_id, completion_id, role, result_schema_version,
                    result_status, input_digest, result_digest, payload_json)
                values (?, ?, ?, ?, 'WIDE_DISCOVERY', 'research-discovery-proposal.v1', 'VALIDATED', ?, ?, json_object())
                """, roleResultId, runId, taskId, completionId, INPUT_DIGEST, "sha256:" + "7".repeat(64));

        ResearchAgentCompletionCommitter.TaskRow task = new ResearchAgentCompletionCommitter.TaskRow(
                taskId, runId, "SUBMITTED", "WIDE_DISCOVERY", "subject", branchId, 0, 0,
                "[]", "{}", EXECUTION_CONTEXT, "research-agent-task-snapshot.v3",
                "sha256:" + "8".repeat(64), "dr110-worker", 1, 1L, "logical-" + taskId, taskId, 1, 1, true);
        return new Fixture(workspaceId, runId, task, roleResultId);
    }

    private String seedRun(String workspaceId) {
        String parentTaskId = Ids.newId();
        String runId = Ids.newId();
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, parentTaskId, workspaceId, runId);
        // json_object keeps this a real JSON object on both MySQL and H2's MySQL mode; a bound
        // VARCHAR would be stored as a JSON string scalar by H2 and break snapshot reads.
        jdbcTemplate.update("""
                insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json,
                    status, agent_feature_flags_json)
                values (?, ?, ?, 'dr110 discovery question', 'DEFAULT', '[]', 'RUNNING',
                    json_object('wide_discovery_auto_accept', true,
                        'wide_discovery_accepted_precision', 0.95,
                        'wide_discovery_precision_threshold', 0.90,
                        'checkpoint_hydration_v2', true))
                """, runId, workspaceId, parentTaskId);
        return runId;
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

    private int countAll() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from research_agent_replan_audit", Integer.class);
        return count == null ? 0 : count;
    }

    private int planRows(String runId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from research_matrix_plan where research_run_id = ?", Integer.class, runId);
        return count == null ? 0 : count;
    }

    private int cells(String runId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from research_cell where research_run_id = ?", Integer.class, runId);
        return count == null ? 0 : count;
    }

    private int proposalRows(String runId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from research_discovery_proposal where research_run_id = ?", Integer.class, runId);
        return count == null ? 0 : count;
    }

    private record Fixture(String workspaceId, String runId,
                           ResearchAgentCompletionCommitter.TaskRow task, String roleResultId) { }
}
