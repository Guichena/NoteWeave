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
class ResearchAgentCandidateIngressServiceTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchAgentTaskService taskService;
    @Autowired private ResearchAgentEvidenceIngestionService evidenceService;
    @Autowired private ResearchAgentCandidateIngressService candidateService;
    @Autowired private ResearchBudgetAndCheckpointService budgetService;

    private String runId;
    private String cellId;
    private String agentTaskId;
    private ResearchAgentTaskService.ClaimedTask claim;

    @BeforeEach
    void setUp() {
        String workspaceId = Ids.newId();
        String parentTaskId = Ids.newId();
        runId = Ids.newId();
        String rowId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', 'ingress-test', 'ACTIVE')", workspaceId);
        jdbcTemplate.update("insert into task(id, workspace_id, task_type, task_status, target_type, target_id) values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)", parentTaskId, workspaceId, runId);
        jdbcTemplate.update("insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json, status, agent_execution_mode) values (?, ?, ?, 'q', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')", runId, workspaceId, parentTaskId);
        jdbcTemplate.update("insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'CANDIDATE_READY')", rowId, runId);
        cellId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key, candidate_value,
                    cell_status, repair_count, cell_version, plan_revision, entity_set_version)
                values (?, ?, ?, 'entity-1:method', 'method', 'old', 'CANDIDATE_READY', 0, 3, 2, 3)
                """, cellId, runId, rowId);
        agentTaskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                runId, "ingress-task", "ingress-idem", 1, "DEEP_CELL", "entity-1", "branch-main", 2, 3,
                List.of("entity-1:method"), Map.of("llm_calls", 2),
                List.of(new ResearchAgentTaskService.TargetCellBinding("entity-1:method", 3)),
                new ResearchAgentTaskService.TaskExecutionContext("research-default",
                        Map.of("source_scope", List.of(Map.of("source_id", "source-1", "title", "Trusted", "sample_text", "The method is documented."))),
                        Map.of("query", "q"))
        )).taskId();
    }

    @Test
    void shouldFailClosedAllThreeSplitCompletionRoutesForAtomicSnapshotTaskWithoutWrites() {
        claim = taskService.claimTask(new ResearchAgentTaskService.ClaimCommand(agentTaskId, "worker-a", 60));

        assertAtomicRequired(() -> evidenceService.appendWorkspaceEvidence(new ResearchAgentEvidenceIngestionService.EvidenceBatchCommand(
                claim.taskId(), "worker-a", claim.leaseEpoch(), claim.fencingToken(), List.of(evidence("The method is documented.")))));
        assertAtomicRequired(() -> candidateService.appendAndVerify(
                new ResearchAgentCandidateIngressService.CandidateBatchCommand(
                        claim.taskId(), "worker-a", claim.leaseEpoch(), claim.fencingToken(), "execution-1",
                        List.of(new ResearchAgentCandidateIngressService.CandidateProposal(
                                "candidate-1", "candidate-idem-1", "entity-1:method", 3,
                                 "The method is documented.", List.of("evidence-1"), 0.9)))));
        assertAtomicRequired(() -> taskService.submitExecution(new ResearchAgentTaskService.SubmitCommand(
                claim.taskId(), "worker-a", claim.leaseEpoch(), claim.fencingToken(), "execution-1",
                "CANDIDATE_BATCH_SUBMITTED", Map.of("llm_calls", 1), "sha256:atomic-guard")));

        assertThat(jdbcTemplate.queryForObject("select count(*) from source_evidence where research_run_id = ?", Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_candidate where research_run_id = ?", Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_cell_merge where research_run_id = ?", Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_execution where research_agent_task_id = ?", Integer.class, agentTaskId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select status from research_agent_task where id = ?", String.class, agentTaskId))
                .isEqualTo("CLAIMED");
        assertThat(jdbcTemplate.queryForObject("select candidate_value from research_cell where id = ?", String.class, cellId))
                .isEqualTo("old");
        assertThat(jdbcTemplate.queryForObject("select cell_version from research_cell where id = ?", Integer.class, cellId)).isEqualTo(3);
    }

    @Test
    void shouldRunLegacyGroundedEvidenceCandidateCasBudgetSettlementAndSubmit() {
        claim = claimLegacy("worker-a");
        budgetService.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                runId, claim.taskId(), "legacy-reserve-1", Map.of("llm_calls", 2L)));

        ResearchAgentEvidenceIngestionService.BatchReceipt evidenceReceipt = evidenceService.appendWorkspaceEvidence(
                new ResearchAgentEvidenceIngestionService.EvidenceBatchCommand(
                        claim.taskId(), "worker-a", claim.leaseEpoch(), claim.fencingToken(),
                        List.of(evidence("The method is documented."))));
        ResearchAgentCandidateIngressService.CandidateBatchReceipt candidateReceipt = candidateService.appendAndVerify(
                new ResearchAgentCandidateIngressService.CandidateBatchCommand(
                claim.taskId(), "worker-a", claim.leaseEpoch(), claim.fencingToken(), "execution-legacy-1",
                List.of(new ResearchAgentCandidateIngressService.CandidateProposal(
                        "candidate-legacy-1", "candidate-legacy-idem-1", "entity-1:method", 3,
                        "The method is documented.", List.of("evidence-1"), 0.9))));
        ResearchAgentTaskService.ExecutionReceipt executionReceipt = taskService.submitExecution(
                new ResearchAgentTaskService.SubmitCommand(
                        claim.taskId(), "worker-a", claim.leaseEpoch(), claim.fencingToken(), "execution-legacy-1",
                        "CANDIDATE_BATCH_SUBMITTED", Map.of("llm_calls", 1), "sha256:legacy"));

        assertThat(evidenceReceipt).isEqualTo(new ResearchAgentEvidenceIngestionService.BatchReceipt(1, 0));
        assertThat(candidateReceipt).isEqualTo(new ResearchAgentCandidateIngressService.CandidateBatchReceipt(1, 0));
        assertThat(executionReceipt.idempotentReplay()).isFalse();
        assertThat(jdbcTemplate.queryForObject("select count(*) from source_evidence where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_candidate where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select decision from research_cell_merge where research_run_id = ?", String.class, runId)).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("select state from research_budget_reservation where research_agent_task_id = ?", String.class, claim.taskId()))
                .isEqualTo("SETTLED");
        assertThat(jdbcTemplate.queryForObject("select consumed_json from research_budget_reservation where research_agent_task_id = ?", String.class, claim.taskId()))
                .contains("\"llm_calls\":1");
        assertThat(jdbcTemplate.queryForObject("select released_json from research_budget_reservation where research_agent_task_id = ?", String.class, claim.taskId()))
                .contains("\"llm_calls\":1");
        assertThat(jdbcTemplate.queryForObject("select candidate_value from research_cell where id = ?", String.class, cellId))
                .isEqualTo("The method is documented.");
        assertThat(jdbcTemplate.queryForObject("select cell_version from research_cell where id = ?", Integer.class, cellId)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("select active_task_id from research_cell where id = ?", String.class, cellId))
                .isNull();
    }

    @Test
    void shouldRejectUngroundedQuoteInLegacyPrimitiveBeforeAnyEvidenceWrite() {
        claim = claimLegacy("worker-a");

        assertBusinessCode(() -> evidenceService.appendWorkspaceEvidence(
                        new ResearchAgentEvidenceIngestionService.EvidenceBatchCommand(
                                claim.taskId(), "worker-a", claim.leaseEpoch(), claim.fencingToken(),
                                List.of(evidence("invented quote")))),
                "RESEARCH_AGENT_EVIDENCE_UNGROUNDED");

        assertThat(jdbcTemplate.queryForObject("select count(*) from source_evidence where research_run_id = ?", Integer.class, runId)).isZero();
    }

    @Test
    void shouldFenceExpiredLegacyWorkerAndAcceptEvidenceOnlyFromCurrentLease() {
        ResearchAgentTaskService.ClaimedTask workerA = claimLegacy("worker-a");
        jdbcTemplate.update("""
                update research_agent_task
                set lease_expires_at = timestampadd(second, -1, current_timestamp)
                where id = ?
                """, agentTaskId);
        taskService.expireLeases();
        ResearchAgentTaskService.ClaimedTask workerB = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(agentTaskId, "worker-b", 60));
        bindLegacyCell(workerB);

        assertThat(workerB.leaseEpoch()).isEqualTo(workerA.leaseEpoch() + 1);
        assertThat(workerB.fencingToken()).isGreaterThan(workerA.fencingToken());
        assertBusinessCode(() -> evidenceService.appendWorkspaceEvidence(
                        new ResearchAgentEvidenceIngestionService.EvidenceBatchCommand(
                                workerA.taskId(), "worker-a", workerA.leaseEpoch(), workerA.fencingToken(),
                                List.of(evidence("The method is documented.")))),
                "RESEARCH_AGENT_TASK_STALE_LEASE");

        ResearchAgentEvidenceIngestionService.BatchReceipt current = evidenceService.appendWorkspaceEvidence(
                new ResearchAgentEvidenceIngestionService.EvidenceBatchCommand(
                        workerB.taskId(), "worker-b", workerB.leaseEpoch(), workerB.fencingToken(),
                        List.of(evidence("The method is documented."))));
        assertThat(current.appendedCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from source_evidence where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select lease_epoch from research_cell where id = ?", Integer.class, cellId))
                .isEqualTo(workerB.leaseEpoch());
    }

    private ResearchAgentEvidenceIngestionService.WorkspaceEvidence evidence(String quote) {
        return new ResearchAgentEvidenceIngestionService.WorkspaceEvidence("evidence-1", "window-1", "source-1", "Trusted", "q", "method",
                quote, "The method is documented.", "SUPPORTS", 0.9, 0.0, "WORKSPACE");
    }

    private void assertAtomicRequired(Runnable operation) {
        assertBusinessCode(operation, "RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED");
    }

    private void assertBusinessCode(Runnable operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo(code);
    }

    private ResearchAgentTaskService.ClaimedTask claimLegacy(String workerId) {
        jdbcTemplate.update("""
                update research_agent_task
                set snapshot_schema_version = null, snapshot_digest = null
                where id = ? and status = 'PENDING'
                """, agentTaskId);
        ResearchAgentTaskService.ClaimedTask legacy = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(agentTaskId, workerId, 60));
        bindLegacyCell(legacy);
        return legacy;
    }

    private void bindLegacyCell(ResearchAgentTaskService.ClaimedTask legacy) {
        int updated = jdbcTemplate.update("""
                update research_cell
                set active_task_id = ?, lease_epoch = ?, fencing_token = ?
                where id = ? and cell_version = 3
                """, legacy.taskId(), legacy.leaseEpoch(), legacy.fencingToken(), cellId);
        assertThat(updated).isEqualTo(1);
    }
}
