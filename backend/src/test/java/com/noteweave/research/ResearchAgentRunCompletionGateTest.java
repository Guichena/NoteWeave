package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.common.Ids;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * DR-305 contract: the RunCompletionGate is the single authority for a Run's business terminal
 * state, and it must let a Run finish honestly when evidence is missing instead of collapsing into
 * the opaque {@code RESEARCH_AGENT_TERMINAL_BARRIER_UNSATISFIED} failure.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResearchAgentRunCompletionGateTest {

    private static final Set<String> REQUIRED = Set.of("answer", "key_evidence");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchAgentRunCompletionGate gate;
    @Autowired private ResearchAgentCoordinatorTickService coordinatorTick;

    // ------------------------------------------------------------------ pure contract (plan §7.3)

    @Test
    void shouldCompleteVerifiedWhenEveryCellIsResolved() {
        var decision = gate.evaluate(
                table("run-verified",
                        cell("entity-1:answer", "answer", "VERIFIED", "A", "ev-1"),
                        cell("entity-1:key_evidence", "key_evidence", "VERIFIED", "E", "ev-2"),
                        cell("entity-1:limitations", "limitations", "VERIFIED", "L", "ev-3")),
                noVerifier(), noBudget(), noInfrastructure());

        assertThat(decision.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.COMPLETED_VERIFIED);
        assertThat(decision.promotableClaims()).hasSize(3);
        assertThat(decision.unresolvedCells()).isEmpty();
        assertThat(decision.reasonCodes()).containsExactly("ALL_REQUIRED_CELLS_VERIFIED");
        assertThat(decision.terminalState().researchRunStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void shouldCompleteWithLimitationsWhenOnlyOptionalCellsRemainUnresolved() {
        var decision = gate.evaluate(
                table("run-limitations",
                        cell("entity-1:answer", "answer", "VERIFIED", "A", "ev-1"),
                        cell("entity-1:key_evidence", "key_evidence", "VERIFIED", "E", "ev-2"),
                        cell("entity-1:limitations", "limitations", "GAP", null)),
                new ResearchAgentRunCompletionGate.VerifierState(List.of(
                        new ResearchAgentRunCompletionGate.CellVerdict(
                                "entity-1:answer", "LOCAL_GLOBAL_VERIFIER_DISAGREEMENT", null))),
                noBudget(), noInfrastructure());

        assertThat(decision.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.COMPLETED_WITH_LIMITATIONS);
        assertThat(decision.unresolvedCells())
                .extracting(ResearchAgentRunCompletionGate.UnresolvedCell::cellKey)
                .containsExactly("entity-1:limitations");
        assertThat(decision.limitations()).contains("UNRESOLVED_CELLS_REMAIN", "VERIFIER_DISAGREEMENT");
        assertThat(decision.reasonCodes()).containsExactly("UNRESOLVED_CELLS_REMAIN");
        assertThat(decision.terminalState().researchRunStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void shouldReportInsufficientEvidenceWhenTheBudgetIsExhaustedWithoutAcceptableEvidence() {
        var decision = gate.evaluate(
                table("run-insufficient",
                        cell("entity-1:answer", "answer", "GAP", null),
                        cell("entity-1:key_evidence", "key_evidence", "GAP", null)),
                noVerifier(),
                new ResearchAgentRunCompletionGate.BudgetState(true, List.of("WALL_CLOCK_BUDGET_EXHAUSTED")),
                noInfrastructure());

        assertThat(decision.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.INSUFFICIENT_EVIDENCE);
        assertThat(decision.terminalState().isInfrastructureFailure()).isFalse();
        assertThat(decision.promotableClaims()).isEmpty();
        assertThat(decision.reasonCodes()).contains("INSUFFICIENT_EVIDENCE", "WALL_CLOCK_BUDGET_EXHAUSTED");
        // A missing-evidence Run stays a business outcome, never an opaque FAILED barrier.
        assertThat(decision.terminalState().researchRunStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void shouldReportInfrastructureFailureWhenTheProviderIsNotConfigured() {
        var decision = gate.evaluate(
                table("run-infrastructure",
                        cell("entity-1:answer", "answer", "GAP", null),
                        cell("entity-1:key_evidence", "key_evidence", "GAP", null)),
                noVerifier(), noBudget(),
                new ResearchAgentRunCompletionGate.InfraState(true, "PROVIDER_NOT_CONFIGURED"));

        assertThat(decision.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.INFRASTRUCTURE_FAILURE);
        assertThat(decision.terminalState().isBusinessCompletion()).isFalse();
        assertThat(decision.reasonCodes()).contains("PROVIDER_NOT_CONFIGURED", "NO_PROMOTABLE_EVIDENCE");
        assertThat(decision.reasonCodes()).doesNotContain("INSUFFICIENT_EVIDENCE");
        assertThat(decision.terminalState().researchRunStatus()).isEqualTo("FAILED");
        assertThat(gate.infrastructureTerminalReason(decision)).isEqualTo("PROVIDER_NOT_CONFIGURED");
    }

    @Test
    void shouldClassifyInvalidProviderJsonAsAnExplicitProviderFailure() {
        assertThat(ResearchAgentRunCompletionGate.normalizeInfrastructureReason("INVALID_JSON"))
                .isEqualTo("PROVIDER_FAILED");
        var decision = gate.evaluate(
                table("run-provider-invalid-json",
                        cell("entity-1:answer", "answer", "GAP", null),
                        cell("entity-1:key_evidence", "key_evidence", "GAP", null)),
                noVerifier(), noBudget(),
                new ResearchAgentRunCompletionGate.InfraState(true, "PROVIDER_FAILED"));
        assertThat(decision.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.INFRASTRUCTURE_FAILURE);
        assertThat(decision.reasonCodes()).contains("PROVIDER_FAILED", "NO_PROMOTABLE_EVIDENCE");
    }

    /** D-9: an infra fault must stay visible even when partial claims still complete. */
    @Test
    void shouldKeepTheInfrastructureReasonVisibleWhenPartialClaimsStillComplete() {
        var decision = gate.evaluate(
                table("run-partial-infrastructure",
                        cell("entity-1:answer", "answer", "VERIFIED", "A", "ev-1"),
                        cell("entity-1:key_evidence", "key_evidence", "VERIFIED", "E", "ev-2"),
                        cell("entity-1:limitations", "limitations", "GAP", null)),
                noVerifier(), noBudget(),
                new ResearchAgentRunCompletionGate.InfraState(true, "PROVIDER_NOT_CONFIGURED"));

        assertThat(decision.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.COMPLETED_WITH_LIMITATIONS);
        assertThat(decision.reasonCodes()).contains("PROVIDER_NOT_CONFIGURED", "UNRESOLVED_CELLS_REMAIN");
    }

    /** D-9: a provider fault recorded after every cell resolved must not vanish from the reasons. */
    @Test
    void shouldKeepTheInfrastructureReasonVisibleWithoutDowngradingAFullyVerifiedRun() {
        var decision = gate.evaluate(
                table("run-verified-infrastructure",
                        cell("entity-1:answer", "answer", "VERIFIED", "A", "ev-1"),
                        cell("entity-1:key_evidence", "key_evidence", "VERIFIED", "E", "ev-2"),
                        cell("entity-1:limitations", "limitations", "VERIFIED", "L", "ev-3")),
                noVerifier(), noBudget(),
                new ResearchAgentRunCompletionGate.InfraState(true, "PROVIDER_NOT_CONFIGURED"));

        assertThat(decision.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.COMPLETED_VERIFIED);
        assertThat(decision.reasonCodes())
                .contains("ALL_REQUIRED_CELLS_VERIFIED", "PROVIDER_NOT_CONFIGURED");
        assertThat(decision.reasonCodes()).doesNotContain("PROVIDER_UNAVAILABLE");
    }

    @Test
    void shouldNeverPromoteAnUnverifiedClaim() {
        var decision = gate.evaluate(
                table("run-unverified",
                        cell("entity-1:answer", "answer", "VERIFIED", "A", "ev-1"),
                        cell("entity-1:key_evidence", "key_evidence", "VERIFIED", "E", "ev-2"),
                        new ResearchAgentRunCompletionGate.CellState(
                                "entity-1:limitations", "limitations", "CANDIDATE_READY",
                                "unverified value", List.of("ev-x"))),
                noVerifier(), noBudget(), noInfrastructure());

        assertThat(decision.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.COMPLETED_WITH_LIMITATIONS);
        assertThat(decision.promotableClaims())
                .extracting(ResearchAgentRunCompletionGate.PromotableClaim::cellKey)
                .containsExactly("entity-1:answer", "entity-1:key_evidence");
        assertThat(decision.promotableClaims())
                .noneMatch(claim -> "unverified value".equals(claim.value()));
    }

    // ------------------------------------------------------------------ coordinator E2E

    @Test
    void shouldCompleteHonestlyOnTheCoordinatorBarrierInsteadOfAnOpaqueFailure() {
        BarrierFixture fixture = seedBarrierRun();

        ResearchAgentCoordinatorTickService.TickReceipt receipt =
                coordinatorTick.tick(fixture.runId(), "completion-gate-test");

        assertThat(receipt.outcome()).isEqualTo("RUN_COMPLETED");
        Map<String, Object> run = jdbcTemplate.queryForMap("""
                select status, completion_terminal_state, completion_reason_codes_json,
                       completion_unresolved_cells_json, completion_promotable_claims_json
                from research_run where id = ?
                """, fixture.runId());
        assertThat(run).containsEntry("status", "COMPLETED")
                .containsEntry("completion_terminal_state", "INSUFFICIENT_EVIDENCE");
        assertThat(String.valueOf(run.get("completion_reason_codes_json"))).contains("INSUFFICIENT_EVIDENCE");
        assertThat(String.valueOf(run.get("completion_unresolved_cells_json")))
                .contains("entity-1:answer", "entity-1:key_evidence");
        assertThat(String.valueOf(run.get("completion_promotable_claims_json"))).isEqualTo("[]");
        assertThat(jdbcTemplate.queryForObject(
                "select task_status from task where id = ?", String.class, fixture.parentTaskId()))
                .isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_trace
                where research_run_id = ? and trace_type = 'RUN_BUSINESS_COMPLETED'
                """, Integer.class, fixture.runId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_agent_report_artifact where research_run_id = ?
                """, Integer.class, fixture.runId())).isEqualTo(1);
        String honestReport = jdbcTemplate.queryForObject(
                "select final_report_markdown from research_run where id = ?", String.class, fixture.runId());
        assertThat(honestReport)
                .contains("# 研究报告", "## 局限与未解决项", "INSUFFICIENT_EVIDENCE", "entity-1:answer",
                        "REQUIRED_CELL_UNRESOLVED", "必需研究项未找到足够证据")
                .doesNotContain("## 已验证结论");
        assertThat(jdbcTemplate.queryForObject(
                "select final_report_title from research_run where id = ?", String.class, fixture.runId()))
                .startsWith("研究报告：");
        // The old opaque barrier reason must no longer be produced.
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task
                where research_run_id = ? and terminal_reason = 'RESEARCH_AGENT_TERMINAL_BARRIER_UNSATISFIED'
                """, Integer.class, fixture.runId())).isZero();
    }

    @Test
    void shouldFailOnTheCoordinatorBarrierWhenTheProviderIsNotConfigured() {
        BarrierFixture fixture = seedBarrierRun();
        jdbcTemplate.update("""
                insert into research_agent_execution(
                    id, research_agent_task_id, execution_key, lease_epoch, fencing_token, worker_instance_id,
                    status, termination_reason, trace_digest, extraction_diagnostics_json)
                values (?, ?, 'completion-gate-exec', 1, 1, 'worker-a', 'SUBMITTED', null, null, ?)
                """, Ids.newId(), fixture.agentTaskId(), "{\"termination_reason\":\"LLM_UNAVAILABLE\"}");

        ResearchAgentCoordinatorTickService.TickReceipt receipt =
                coordinatorTick.tick(fixture.runId(), "completion-gate-test");

        assertThat(receipt.outcome()).isEqualTo("RUN_FAILED_INFRASTRUCTURE");
        Map<String, Object> run = jdbcTemplate.queryForMap("""
                select status, completion_terminal_state, completion_reason_codes_json
                from research_run where id = ?
                """, fixture.runId());
        assertThat(run).containsEntry("status", "FAILED")
                .containsEntry("completion_terminal_state", "INFRASTRUCTURE_FAILURE");
        assertThat(String.valueOf(run.get("completion_reason_codes_json"))).contains("PROVIDER_NOT_CONFIGURED");
        assertThat(jdbcTemplate.queryForObject(
                "select task_status from task where id = ?", String.class, fixture.parentTaskId()))
                .isEqualTo("FAILED");
    }

    @Test
    void shouldSurfaceNoValidQuoteWhenEveryReturnedQuoteWasNonExact() {
        BarrierFixture fixture = seedBarrierRun();
        jdbcTemplate.update("""
                insert into research_agent_execution(
                    id, research_agent_task_id, execution_key, lease_epoch, fencing_token, worker_instance_id,
                    status, termination_reason, trace_digest, extraction_diagnostics_json)
                values (?, ?, 'quote-tamper-exec', 1, 1, 'worker-a', 'SUBMITTED', null, null, ?)
                """, Ids.newId(), fixture.agentTaskId(), """
                {"termination_reason":"ALL_CARDS_REJECTED","accepted_count":0,"rejected_count":2,
                 "rejection_counts":{"NON_EXACT_QUOTE":2}}
                """);

        var decision = gate.evaluateForRun(fixture.runId());

        assertThat(decision.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.INSUFFICIENT_EVIDENCE);
        assertThat(decision.reasonCodes()).contains("INSUFFICIENT_EVIDENCE", "NO_VALID_QUOTE");
        assertThat(decision.promotableClaims()).isEmpty();
    }

    // ------------------------------------------------------------------ builders

    private static ResearchAgentRunCompletionGate.TableState table(
            String runId, ResearchAgentRunCompletionGate.CellState... cells) {
        return new ResearchAgentRunCompletionGate.TableState(runId, List.of(cells), REQUIRED);
    }

    private static ResearchAgentRunCompletionGate.CellState cell(
            String key, String column, String status, String value, String... evidence) {
        return new ResearchAgentRunCompletionGate.CellState(key, column, status, value, List.of(evidence));
    }

    private static ResearchAgentRunCompletionGate.VerifierState noVerifier() {
        return new ResearchAgentRunCompletionGate.VerifierState(List.of());
    }

    private static ResearchAgentRunCompletionGate.BudgetState noBudget() {
        return new ResearchAgentRunCompletionGate.BudgetState(false, List.of());
    }

    private static ResearchAgentRunCompletionGate.InfraState noInfrastructure() {
        return new ResearchAgentRunCompletionGate.InfraState(false, "");
    }

    private BarrierFixture seedBarrierRun() {
        String workspaceId = Ids.newId();
        String parentTaskId = Ids.newId();
        String runId = Ids.newId();
        String rowId = Ids.newId();
        String agentTaskId = Ids.newId();
        jdbcTemplate.update(
                "insert into workspace(id, owner_id, name, status) values (?, 'local-user', 'completion-gate', 'ACTIVE')",
                workspaceId);
        jdbcTemplate.update(
                "insert into task(id, workspace_id, task_type, task_status, target_type, target_id) "
                        + "values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)",
                parentTaskId, workspaceId, runId);
        jdbcTemplate.update("""
                insert into research_run(
                    id, workspace_id, task_id, question, profile_key, source_scope_json, status, agent_execution_mode)
                values (?, ?, ?, 'Can missing evidence complete honestly?', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')
                """, runId, workspaceId, parentTaskId);
        jdbcTemplate.update(
                "insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'GAP')",
                rowId, runId);
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key, cell_status, repair_count)
                values (?, ?, ?, 'entity-1:answer', 'answer', 'GAP', 0)
                """, Ids.newId(), runId, rowId);
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key, cell_status, repair_count)
                values (?, ?, ?, 'entity-1:key_evidence', 'key_evidence', 'GAP', 0)
                """, Ids.newId(), runId, rowId);
        jdbcTemplate.update("""
                insert into research_agent_task(
                    id, research_run_id, task_key, idempotency_key, wave_no, role, entity_id,
                    branch_id, plan_revision, entity_set_version, target_cells_json, budget_json, status)
                values (?, ?, 'completion-gate-task', 'completion-gate-task', 1, 'DEEP_CELL',
                    'entity-1', 'branch-main', 1, 1, '["entity-1:answer"]', '{}', 'SUBMITTED')
                """, agentTaskId, runId);
        return new BarrierFixture(runId, parentTaskId, agentTaskId);
    }

    private record BarrierFixture(String runId, String parentTaskId, String agentTaskId) { }
}
