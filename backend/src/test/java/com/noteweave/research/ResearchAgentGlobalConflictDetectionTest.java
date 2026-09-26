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
 * DR-303 contract: local and global conflict adjudication on the Java canonical ledger.
 *
 * <p>The judgement is a pure function of persisted canonical facts — no LLM, no Worker verdict. A
 * <b>global</b> conflict exists only when independent sources disagree on a single cell: either their
 * typed facts are mutually exclusive, or a {@code CONFLICTS} relation coexists with supporting
 * evidence. Same-source self-contradiction stays a local problem, and a lone {@code CONFLICTS}
 * relation is not a conflict.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ResearchAgentGlobalConflictDetectionTest {

    private static final Set<String> REQUIRED = Set.of("answer", "key_evidence");
    private static final String LINEAGE_A = "a".repeat(64);
    private static final String LINEAGE_B = "b".repeat(64);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchGlobalConflictService conflictService;
    @Autowired private ResearchAgentRunCompletionGate gate;
    @Autowired private ResearchAgentCoordinatorTickService coordinatorTick;

    // ------------------------------------------------------------------ positive: cross-source typed facts

    @Test
    void shouldRecordCrossSourceTypedFactConflictAndSurfaceItOnTheGate() {
        RunFixture fixture = seedRun();
        String cellId = seedCell(fixture, "entity-1:answer", "answer", "CANDIDATE_READY", "candidate-value");
        bind(fixture, cellId, seedEvidence(fixture, "ev-a", "alpha.com", LINEAGE_A,
                "SUPPORTS", "revenue was 100", "revenue was 100"));
        bind(fixture, cellId, seedEvidence(fixture, "ev-b", "beta.org", LINEAGE_B,
                "SUPPORTS", "revenue was 200", "revenue was 200"));

        assertThat(conflictService.adjudicateRun(fixture.runId())).isEqualTo(1);
        assertThat(conflictService.adjudicateRun(fixture.runId())).isEqualTo(1);

        // 1. the conflict trace is persisted and queryable (idempotently, one row per cell+kind)
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_verifier_decision
                where research_run_id = ? and decision_type = 'GLOBAL_CONFLICT'
                """, Integer.class, fixture.runId())).isEqualTo(1);
        Map<String, Object> trace = jdbcTemplate.queryForMap("""
                select target_id, reason_code, evidence_ids_json, action_text, decision_status
                from research_verifier_decision
                where research_run_id = ? and decision_type = 'GLOBAL_CONFLICT'
                """, fixture.runId());
        assertThat(trace).containsEntry("target_id", "entity-1:answer")
                .containsEntry("reason_code", "NUMBER_VALUE_CONFLICT")
                .containsEntry("decision_status", "OPEN");
        assertThat(String.valueOf(trace.get("evidence_ids_json"))).contains("ev-a", "ev-b");
        String rationale = String.valueOf(trace.get("action_text"));
        assertThat(rationale).contains("TYPED_FACT_CONTRADICTION", "NUMBER_VALUE_CONFLICT");
        // The rationale is a bounded, model-free summary: no claim text, no reasoning chain.
        assertThat(rationale).doesNotContain("revenue");
        assertThat(rationale.length()).isLessThanOrEqualTo(512);

        // 2. the cell is explicitly conflicted; the old value is kept only for audit
        Map<String, Object> cell = jdbcTemplate.queryForMap("""
                select cell_status, candidate_value, last_verifier_decision
                from research_cell where id = ?
                """, cellId);
        assertThat(cell).containsEntry("cell_status", "CONFLICTED")
                .containsEntry("candidate_value", "candidate-value");
        assertThat(String.valueOf(cell.get("last_verifier_decision")))
                .contains("GLOBAL_CONFLICT", "TYPED_FACT_CONTRADICTION");

        // 3. the gate emits a conflict-specific reason and never promotes the cell
        ResearchAgentRunCompletionGate.CompletionDecision conflictDecision = gate.evaluateForRun(fixture.runId());
        assertThat(conflictDecision.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.INSUFFICIENT_EVIDENCE);
        assertThat(conflictDecision.reasonCodes()).contains("INSUFFICIENT_EVIDENCE", "CONFLICTED_CELL");
        assertThat(conflictDecision.unresolvedCells()).singleElement().satisfies(unresolved -> {
            assertThat(unresolved.cellKey()).isEqualTo("entity-1:answer");
            assertThat(unresolved.reasonCode()).isEqualTo("CONFLICTED_CELL");
        });
        assertThat(conflictDecision.promotableClaims()).isEmpty();

        // 4. distinguishable from "no evidence was ever obtained"
        RunFixture missing = seedRun();
        seedCell(missing, "entity-1:answer", "answer", "GAP", null);
        ResearchAgentRunCompletionGate.CompletionDecision missingDecision = gate.evaluateForRun(missing.runId());
        assertThat(missingDecision.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.INSUFFICIENT_EVIDENCE);
        assertThat(missingDecision.unresolvedCells()).singleElement()
                .extracting(ResearchAgentRunCompletionGate.UnresolvedCell::reasonCode)
                .isEqualTo("REQUIRED_CELL_UNRESOLVED");
        assertThat(missingDecision.reasonCodes()).doesNotContain("CONFLICTED_CELL");
    }

    @Test
    void shouldTreatCaseDifferingSameSkeletonWithDifferentValuesAsAGlobalConflict() {
        RunFixture fixture = seedRun();
        String cellId = seedCell(fixture, "entity-1:answer", "answer", "CANDIDATE_READY", "old-0");
        bind(fixture, cellId, seedEvidence(fixture, "ev-a", "alpha.com", LINEAGE_A,
                "SUPPORTS", "Revenue was 100", "Revenue was 100"));
        bind(fixture, cellId, seedEvidence(fixture, "ev-b", "beta.org", LINEAGE_B,
                "SUPPORTS", "revenue was 200", "revenue was 200"));

        assertThat(conflictService.adjudicateRun(fixture.runId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select reason_code from research_verifier_decision
                where research_run_id = ? and decision_type = 'GLOBAL_CONFLICT'
                """, String.class, fixture.runId())).isEqualTo("NUMBER_VALUE_CONFLICT");
        assertThat(jdbcTemplate.queryForObject(
                "select cell_status from research_cell where id = ?", String.class, cellId))
                .isEqualTo("CONFLICTED");
    }

    // -------------------------------------------------------------- negative 1: same-source self-contradiction

    @Test
    void shouldNotTreatSameSourceSelfContradictionAsAGlobalConflict() {
        RunFixture fixture = seedRun();
        String cellId = seedCell(fixture, "entity-1:answer", "answer", "CANDIDATE_READY", "old-0");
        bind(fixture, cellId, seedEvidence(fixture, "ev-a1", "alpha.com", LINEAGE_A,
                "SUPPORTS", "revenue was 100", "revenue was 100"));
        bind(fixture, cellId, seedEvidence(fixture, "ev-a2", "alpha.com", LINEAGE_A,
                "SUPPORTS", "revenue was 200", "revenue was 200"));

        assertThat(conflictService.adjudicateRun(fixture.runId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select cell_status from research_cell where id = ?", String.class, cellId))
                .isEqualTo("CANDIDATE_READY");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_verifier_decision
                where research_run_id = ? and decision_type = 'GLOBAL_CONFLICT'
                """, Integer.class, fixture.runId())).isZero();
    }

    // -------------------------------------------------------------- negative 2: lone CONFLICTS relation

    @Test
    void shouldNotTreatALoneConflictsRelationAsAGlobalConflict() {
        RunFixture fixture = seedRun();
        String cellId = seedCell(fixture, "entity-1:answer", "answer", "CANDIDATE_READY", "old-0");
        bind(fixture, cellId, seedEvidence(fixture, "ev-c1", "alpha.com", LINEAGE_A,
                "CONFLICTS", "alpha statement", "alpha statement"));
        bind(fixture, cellId, seedEvidence(fixture, "ev-c2", "beta.org", LINEAGE_B,
                "CONFLICTS", "beta statement", "beta statement"));

        assertThat(conflictService.adjudicateRun(fixture.runId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select cell_status from research_cell where id = ?", String.class, cellId))
                .isEqualTo("CANDIDATE_READY");
    }

    // -------------------------------------------------------------- positive: CONFLICTS coexists with support

    @Test
    void shouldRecordConflictingRelationCoexistingWithSupport() {
        RunFixture fixture = seedRun();
        String cellId = seedCell(fixture, "entity-1:answer", "answer", "CANDIDATE_READY", "old-0");
        bind(fixture, cellId, seedEvidence(fixture, "ev-s", "alpha.com", LINEAGE_A,
                "SUPPORTS", "alpha statement", "alpha statement"));
        bind(fixture, cellId, seedEvidence(fixture, "ev-c", "beta.org", LINEAGE_B,
                "CONFLICTS", "beta statement", "beta statement"));

        assertThat(conflictService.adjudicateRun(fixture.runId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select reason_code from research_verifier_decision
                where research_run_id = ? and decision_type = 'GLOBAL_CONFLICT'
                """, String.class, fixture.runId())).isEqualTo("CONFLICTS_RELATION_WITH_SUPPORT");
        assertThat(jdbcTemplate.queryForObject(
                "select cell_status from research_cell where id = ?", String.class, cellId))
                .isEqualTo("CONFLICTED");
    }

    // -------------------------------------------------------------- negative 3: different date contexts

    @Test
    void shouldNotTreatDifferentDateContextsAsAGlobalConflict() {
        RunFixture fixture = seedRun();
        String cellId = seedCell(fixture, "entity-1:answer", "answer", "CANDIDATE_READY", "old-0");
        bind(fixture, cellId, seedEvidence(fixture, "ev-a", "alpha.com", LINEAGE_A,
                "SUPPORTS", "2023 revenue was 100", "2023 revenue was 100"));
        bind(fixture, cellId, seedEvidence(fixture, "ev-b", "beta.org", LINEAGE_B,
                "SUPPORTS", "2024 revenue was 200", "2024 revenue was 200"));

        assertThat(conflictService.adjudicateRun(fixture.runId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select cell_status from research_cell where id = ?", String.class, cellId))
                .isEqualTo("CANDIDATE_READY");
    }

    // -------------------------------------------------------------- negative 4: different subjects

    @Test
    void shouldNotTreatDifferentSubjectsAsAGlobalConflict() {
        RunFixture fixture = seedRun();
        String cellId = seedCell(fixture, "entity-1:answer", "answer", "CANDIDATE_READY", "old-0");
        bind(fixture, cellId, seedEvidence(fixture, "ev-a", "alpha.com", LINEAGE_A,
                "SUPPORTS", "revenue was 100", "revenue was 100"));
        bind(fixture, cellId, seedEvidence(fixture, "ev-b", "beta.org", LINEAGE_B,
                "SUPPORTS", "cost was 200", "cost was 200"));

        assertThat(conflictService.adjudicateRun(fixture.runId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select cell_status from research_cell where id = ?", String.class, cellId))
                .isEqualTo("CANDIDATE_READY");
    }

    // -------------------------------------------------------------- negative 5: identical value, different date

    @Test
    void shouldNotTreatMatchingValuesWithDifferentDatesAsAGlobalConflict() {
        RunFixture fixture = seedRun();
        String cellId = seedCell(fixture, "entity-1:answer", "answer", "CANDIDATE_READY", "old-0");
        bind(fixture, cellId, seedEvidence(fixture, "ev-a", "alpha.com", LINEAGE_A,
                "SUPPORTS", "revenue was 100 in 2023", "revenue was 100 in 2023"));
        bind(fixture, cellId, seedEvidence(fixture, "ev-b", "beta.org", LINEAGE_B,
                "SUPPORTS", "revenue was 100 in 2024", "revenue was 100 in 2024"));

        assertThat(conflictService.adjudicateRun(fixture.runId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select cell_status from research_cell where id = ?", String.class, cellId))
                .isEqualTo("CANDIDATE_READY");
    }

    // -------------------------------------------------------------- negative 6: same-source support + oppose

    @Test
    void shouldNotTreatSameSourceSupportAndOpposeAsAGlobalConflict() {
        RunFixture fixture = seedRun();
        String cellId = seedCell(fixture, "entity-1:answer", "answer", "CANDIDATE_READY", "old-0");
        bind(fixture, cellId, seedEvidence(fixture, "ev-s", "alpha.com", LINEAGE_A,
                "SUPPORTS", "alpha statement", "alpha statement"));
        bind(fixture, cellId, seedEvidence(fixture, "ev-c", "alpha.com", LINEAGE_A,
                "CONFLICTS", "beta statement", "beta statement"));

        assertThat(conflictService.adjudicateRun(fixture.runId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select cell_status from research_cell where id = ?", String.class, cellId))
                .isEqualTo("CANDIDATE_READY");
    }

    // ------------------------------------------------------------------ gate: required vs optional column

    @Test
    void shouldReportAConflictCellInARequiredColumnDistinctFromMissingEvidence() {
        ResearchAgentRunCompletionGate.CompletionDecision conflicted = gate.evaluate(
                table(new ResearchAgentRunCompletionGate.CellState(
                        "entity-1:answer", "answer", "CONFLICTED", "old", List.of("ev-1"))),
                noVerifier(), noBudget(), noInfrastructure());
        ResearchAgentRunCompletionGate.CompletionDecision missing = gate.evaluate(
                table(new ResearchAgentRunCompletionGate.CellState(
                        "entity-1:answer", "answer", "GAP", null, List.of())),
                noVerifier(), noBudget(), noInfrastructure());

        // Terminal state stays the honest business outcome for both, but the reason is conflict-specific.
        assertThat(conflicted.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.INSUFFICIENT_EVIDENCE);
        assertThat(missing.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.INSUFFICIENT_EVIDENCE);
        assertThat(conflicted.reasonCodes()).contains("CONFLICTED_CELL");
        assertThat(missing.reasonCodes()).doesNotContain("CONFLICTED_CELL");
        assertThat(conflicted.unresolvedCells()).singleElement()
                .extracting(ResearchAgentRunCompletionGate.UnresolvedCell::reasonCode)
                .isEqualTo("CONFLICTED_CELL");
        assertThat(missing.unresolvedCells()).singleElement()
                .extracting(ResearchAgentRunCompletionGate.UnresolvedCell::reasonCode)
                .isEqualTo("REQUIRED_CELL_UNRESOLVED");
        assertThat(conflicted.limitations()).contains("CONFLICTED_CELL");
        assertThat(missing.limitations()).doesNotContain("CONFLICTED_CELL");
    }

    @Test
    void shouldReportAConflictCellInAnOptionalColumnDistinctFromMissingEvidence() {
        ResearchAgentRunCompletionGate.CompletionDecision conflicted = gate.evaluate(
                table(
                        cell("entity-1:answer", "answer", "VERIFIED", "A", "ev-1"),
                        cell("entity-1:key_evidence", "key_evidence", "VERIFIED", "E", "ev-2"),
                        new ResearchAgentRunCompletionGate.CellState(
                                "entity-1:limitations", "limitations", "CONFLICTED", "old", List.of("ev-3"))),
                noVerifier(), noBudget(), noInfrastructure());
        ResearchAgentRunCompletionGate.CompletionDecision missing = gate.evaluate(
                table(
                        cell("entity-1:answer", "answer", "VERIFIED", "A", "ev-1"),
                        cell("entity-1:key_evidence", "key_evidence", "VERIFIED", "E", "ev-2"),
                        cell("entity-1:limitations", "limitations", "GAP", null)),
                noVerifier(), noBudget(), noInfrastructure());

        assertThat(conflicted.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.COMPLETED_WITH_LIMITATIONS);
        assertThat(missing.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.COMPLETED_WITH_LIMITATIONS);
        assertThat(conflicted.reasonCodes()).contains("UNRESOLVED_CELLS_REMAIN", "CONFLICTED_CELL");
        assertThat(missing.reasonCodes()).doesNotContain("CONFLICTED_CELL");
        assertThat(conflicted.unresolvedCells()).singleElement()
                .extracting(ResearchAgentRunCompletionGate.UnresolvedCell::reasonCode)
                .isEqualTo("CONFLICTED_CELL");
        assertThat(missing.unresolvedCells()).singleElement()
                .extracting(ResearchAgentRunCompletionGate.UnresolvedCell::reasonCode)
                .isEqualTo("OPTIONAL_CELL_UNRESOLVED");
    }

    // ------------------------------------------------------------------ coordinator wiring

    /**
     * Coverage note (DR-304): this fixture is an <b>incomplete</b> run — it has no research intent /
     * control pack and an empty source scope — so {@code ResearchAgentConflictRepairService.advance}
     * finds the counterfactual repair infeasible and leaves the conflict OPEN. What this case
     * therefore pins is the <b>"conflict cannot be counterfactually repaired"</b> path: the tick
     * still reaches the gate and completes honestly with {@code CONFLICTED_CELL}.
     *
     * <p>For a <em>fully-formed</em> run the conflict is dispatched to a bounded counterfactual repair
     * first and the run does not complete on that tick; that behaviour is covered by
     * {@code ResearchAgentConflictRepairTest#shouldDispatchARepairOnTheCoordinatorTickBeforeTheRunCanComplete}.</p>
     */
    @Test
    void shouldAdjudicateConflictsOnTheCoordinatorBarrierWithoutFinalizingTheRun() {
        RunFixture fixture = seedRun();
        String cellId = seedCell(fixture, "entity-1:answer", "answer", "CANDIDATE_READY", "candidate-value");
        bind(fixture, cellId, seedEvidence(fixture, "ev-a", "alpha.com", LINEAGE_A,
                "SUPPORTS", "revenue was 100", "revenue was 100"));
        bind(fixture, cellId, seedEvidence(fixture, "ev-b", "beta.org", LINEAGE_B,
                "SUPPORTS", "revenue was 200", "revenue was 200"));
        jdbcTemplate.update("""
                insert into research_agent_task(
                    id, research_run_id, task_key, idempotency_key, wave_no, role, entity_id,
                    branch_id, plan_revision, entity_set_version, target_cells_json, budget_json, status)
                values (?, ?, 'dr303-task', 'dr303-task', 1, 'DEEP_CELL', 'entity-1',
                    'branch-main', 1, 1, '["entity-1:answer"]', '{}', 'SUBMITTED')
                """, Ids.newId(), fixture.runId());

        ResearchAgentCoordinatorTickService.TickReceipt receipt =
                coordinatorTick.tick(fixture.runId(), "dr303-scheduler");

        assertThat(receipt.outcome()).isEqualTo("RUN_COMPLETED");
        Map<String, Object> run = jdbcTemplate.queryForMap("""
                select status, completion_terminal_state, completion_reason_codes_json,
                       completion_unresolved_cells_json
                from research_run where id = ?
                """, fixture.runId());
        assertThat(run).containsEntry("status", "COMPLETED")
                .containsEntry("completion_terminal_state", "INSUFFICIENT_EVIDENCE");
        assertThat(String.valueOf(run.get("completion_reason_codes_json"))).contains("CONFLICTED_CELL");
        assertThat(String.valueOf(run.get("completion_unresolved_cells_json")))
                .contains("entity-1:answer", "CONFLICTED_CELL");
        assertThat(jdbcTemplate.queryForObject(
                "select cell_status from research_cell where id = ?", String.class, cellId))
                .isEqualTo("CONFLICTED");
    }

    // ------------------------------------------------------------------ builders / fixture plumbing

    private static ResearchAgentRunCompletionGate.TableState table(
            ResearchAgentRunCompletionGate.CellState... cells) {
        return new ResearchAgentRunCompletionGate.TableState("run-dr303", List.of(cells), REQUIRED);
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

    private RunFixture seedRun() {
        String workspaceId = Ids.newId();
        String parentTaskId = Ids.newId();
        String runId = Ids.newId();
        String rowId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')",
                workspaceId, "dr303-" + runId);
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, parentTaskId, workspaceId, runId);
        jdbcTemplate.update("""
                insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json,
                    status, agent_execution_mode)
                values (?, ?, ?, 'dr303 question', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')
                """, runId, workspaceId, parentTaskId);
        jdbcTemplate.update(
                "insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'CANDIDATE_READY')",
                rowId, runId);
        return new RunFixture(runId, rowId);
    }

    private String seedCell(RunFixture fixture, String cellKey, String columnKey, String status, String value) {
        String cellId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key,
                    candidate_value, cell_status, repair_count)
                values (?, ?, ?, ?, ?, ?, ?, 0)
                """, cellId, fixture.runId(), fixture.rowId(), cellKey, columnKey, value, status);
        return cellId;
    }

    private String seedEvidence(RunFixture fixture, String evidenceKey, String domain, String lineage,
                                String relation, String claim, String quote) {
        String evidenceId = Ids.newId();
        jdbcTemplate.update("""
                insert into source_evidence(id, research_run_id, evidence_key, source_id, relation_type,
                    claim_text, quote_text, source_domain, lineage_digest, snapshot_status)
                values (?, ?, ?, 'source-0', ?, ?, ?, ?, ?, 'WORKSPACE')
                """, evidenceId, fixture.runId(), evidenceKey, relation, claim, quote, domain, lineage);
        return evidenceId;
    }

    private void bind(RunFixture fixture, String cellId, String evidenceId) {
        String evidenceKey = jdbcTemplate.queryForObject(
                "select evidence_key from source_evidence where id = ?", String.class, evidenceId);
        jdbcTemplate.update("""
                insert into research_cell_evidence(
                    id, research_run_id, research_cell_id, source_evidence_id, evidence_key)
                values (?, ?, ?, ?, ?)
                """, Ids.newId(), fixture.runId(), cellId, evidenceId, evidenceKey);
    }

    private record RunFixture(String runId, String rowId) { }
}
