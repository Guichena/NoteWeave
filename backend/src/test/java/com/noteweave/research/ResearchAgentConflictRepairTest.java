package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
 * DR-304 contract: bounded counterfactual repair for DR-303 global conflicts.
 *
 * <p>A conflict produces a single cell-scoped counterfactual task anchored to the conflict trace;
 * the attempt count is independently metered and hard-bounded; the bound is recorded explicitly
 * rather than stopped silently; and the conflict can converge (the cell leaves {@code CONFLICTED}).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ResearchAgentConflictRepairTest {

    private static final String LINEAGE_A = "a".repeat(64);
    private static final String LINEAGE_B = "b".repeat(64);
    private static final String LINEAGE_C = "c".repeat(64);
    private static final String LINEAGE_D = "d".repeat(64);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ResearchGlobalConflictService conflictService;
    @Autowired private ResearchAgentConflictRepairService conflictRepairService;
    @Autowired private ResearchAgentRunCompletionGate gate;
    @Autowired private ResearchAgentCoordinatorTickService coordinatorTick;

    // ------------------------------------------------------------------ 1. dispatch + anchor

    @Test
    void shouldDispatchASingleCellConflictRepairAnchoredToTheTrace() throws Exception {
        Fixture fixture = seedRun();
        seedCell(fixture, "entity-1:answer", "answer");
        bindConflict(fixture, "ev-a", "alpha.com", LINEAGE_A, "revenue was 100");
        bindConflict(fixture, "ev-b", "beta.org", LINEAGE_B, "revenue was 200");

        assertThat(conflictService.adjudicateRun(fixture.runId())).isEqualTo(1);
        assertThat(cellStatus(fixture, "entity-1:answer")).isEqualTo("CONFLICTED");
        String traceId = jdbcTemplate.queryForObject("""
                select id from research_verifier_decision
                where research_run_id = ? and decision_type = 'GLOBAL_CONFLICT'
                """, String.class, fixture.runId());

        ResearchAgentConflictRepairService.ConflictRepairReceipt receipt =
                conflictRepairService.advance(fixture.runId());

        assertThat(receipt.dispatched()).isEqualTo(1);
        assertThat(receipt.exhausted()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task
                where research_run_id = ? and role = 'COUNTERFACTUAL'
                """, Integer.class, fixture.runId())).isEqualTo(1);
        Map<String, Object> task = jdbcTemplate.queryForMap("""
                select target_cells_json, execution_context_json from research_agent_task
                where research_run_id = ? and logical_task_key like 'conflict-counterfactual:%'
                """, fixture.runId());
        // The counterfactual targets exactly the conflicted cell, nothing else.
        assertThat(String.valueOf(task.get("target_cells_json"))).isEqualTo("[\"entity-1:answer\"]");
        // ...and its context names the exact conflict trace and the evidence under dispute.
        JsonNode queryPolicy = objectMapper.readTree(String.valueOf(task.get("execution_context_json")))
                .path("query_policy");
        assertThat(queryPolicy.path("conflict_trace_id").asText()).isEqualTo(traceId);
        assertThat(queryPolicy.path("conflict_evidence_ids").toString()).contains("ev-a", "ev-b");

        assertThat(conflictRepairService.conflictRepairLedger(fixture.runId()).attempts()).isEqualTo(1);
    }

    /**
     * The ordering guarantee: on a fully-formed run the tick dispatches the bounded repair *before*
     * the snapshot, so the run cannot sail past an open conflict and complete on the same tick.
     */
    @Test
    void shouldDispatchARepairOnTheCoordinatorTickBeforeTheRunCanComplete() {
        Fixture fixture = seedRun();
        seedCell(fixture, "entity-1:answer", "answer");
        bindConflict(fixture, "ev-a", "alpha.com", LINEAGE_A, "revenue was 100");
        bindConflict(fixture, "ev-b", "beta.org", LINEAGE_B, "revenue was 200");

        ResearchAgentCoordinatorTickService.TickReceipt receipt =
                coordinatorTick.tick(fixture.runId(), "dr304-scheduler");

        assertThat(receipt.outcome()).isEqualTo("ACTIVE_NOOP");
        assertThat(conflictRepairTasks(fixture)).isEqualTo(1);
        assertThat(cellStatus(fixture, "entity-1:answer")).isEqualTo("CONFLICTED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_run where id = ?", String.class, fixture.runId()))
                .isEqualTo("RUNNING");
    }

    // ------------------------------------------------------------------ 2. explicit exhaustion

    @Test
    void shouldExplicitlyRecordExhaustionInsteadOfStoppingSilently() {
        Fixture fixture = seedConflict();
        assertThat(conflictRepairService.advance(fixture.runId()).dispatched()).isEqualTo(1);
        simulateRepairCompleted(fixture, "entity-1:answer");

        ResearchAgentConflictRepairService.ConflictRepairReceipt second =
                conflictRepairService.advance(fixture.runId());

        assertThat(second.dispatched()).isZero();
        assertThat(second.exhausted()).isEqualTo(1);
        // The conflict trace leaves OPEN and enters an explicit terminal state...
        assertThat(jdbcTemplate.queryForObject("""
                select decision_status from research_verifier_decision
                where research_run_id = ? and decision_type = 'GLOBAL_CONFLICT' and target_id = 'entity-1:answer'
                """, String.class, fixture.runId())).isEqualTo("EXHAUSTED");
        // ...and a stable reason code is recorded, not just an absence of work.
        assertThat(jdbcTemplate.queryForObject("""
                select reason_code from research_verifier_decision
                where research_run_id = ? and decision_type = 'GLOBAL_CONFLICT_REPAIR_EXHAUSTED'
                """, String.class, fixture.runId())).isEqualTo("CONFLICT_REPAIR_EXHAUSTED");
        assertThat(cellStatus(fixture, "entity-1:answer")).isEqualTo("CONFLICT_EXHAUSTED");
    }

    // ------------------------------------------------------------------ 3. convergence

    @Test
    void shouldLetTheCellLeaveConflictedWhenIndependentlyCorroborated() {
        Fixture fixture = seedRun();
        seedCell(fixture, "entity-1:answer", "answer");
        bindConflict(fixture, "ev-a", "alpha.com", LINEAGE_A, "revenue was 100");
        bindConflict(fixture, "ev-b", "beta.org", LINEAGE_B, "revenue was 200");
        assertThat(conflictService.adjudicateRun(fixture.runId())).isEqualTo(1);
        assertThat(cellStatus(fixture, "entity-1:answer")).isEqualTo("CONFLICTED");

        // A new independent source agrees with one side: the conflict is now out-voted, not open.
        bindEvidence(fixture, "entity-1:answer", "ev-c", "gamma.net", LINEAGE_C, "revenue was 100");

        assertThat(conflictService.adjudicateRun(fixture.runId())).isZero();
        assertThat(cellStatus(fixture, "entity-1:answer")).isEqualTo("CANDIDATE_READY");
        assertThat(jdbcTemplate.queryForObject("""
                select decision_status from research_verifier_decision
                where research_run_id = ? and decision_type = 'GLOBAL_CONFLICT' and target_id = 'entity-1:answer'
                """, String.class, fixture.runId())).isEqualTo("RESOLVED");

        // The gate no longer reports a conflict at all.
        ResearchAgentRunCompletionGate.CompletionDecision decision = gate.evaluateForRun(fixture.runId());
        assertThat(decision.reasonCodes()).doesNotContain("CONFLICTED_CELL");
        assertThat(decision.unresolvedCells()).singleElement()
                .extracting(ResearchAgentRunCompletionGate.UnresolvedCell::reasonCode)
                .isEqualTo("REQUIRED_CELL_UNRESOLVED");
    }

    /**
     * The voting domain must be the conflicted fact domain, not the whole cell: two corroborating
     * sources that talk about a different subject must not be able to out-vote a live conflict.
     */
    @Test
    void shouldNotResolveAConflictFromEvidenceOutsideTheConflictedFactDomain() {
        Fixture fixture = seedRun();
        seedCell(fixture, "entity-1:answer", "answer");
        bindConflict(fixture, "ev-a", "alpha.com", LINEAGE_A, "revenue was 100");
        bindConflict(fixture, "ev-b", "beta.org", LINEAGE_B, "revenue was 200");
        assertThat(conflictService.adjudicateRun(fixture.runId())).isEqualTo(1);

        // Two independent sources agree on a *cost* figure. It is corroboration for cost, not for
        // revenue, so it must not un-conflict the revenue cell.
        bindEvidence(fixture, "entity-1:answer", "ev-c", "gamma.net", LINEAGE_C, "cost was 500");
        bindEvidence(fixture, "entity-1:answer", "ev-d", "delta.io", LINEAGE_D, "cost was 500");

        assertThat(conflictService.adjudicateRun(fixture.runId())).isEqualTo(1);
        assertThat(cellStatus(fixture, "entity-1:answer")).isEqualTo("CONFLICTED");
        assertThat(jdbcTemplate.queryForObject("""
                select decision_status from research_verifier_decision
                where research_run_id = ? and decision_type = 'GLOBAL_CONFLICT' and target_id = 'entity-1:answer'
                """, String.class, fixture.runId())).isEqualTo("OPEN");
    }

    // ------------------------------------------------------------------ 4. exhaustion is a limitation

    @Test
    void shouldReportAnExplicitLimitationWhenTheBoundedRepairDidNotResolveTheConflict() {
        Fixture fixture = seedConflict();
        conflictRepairService.advance(fixture.runId());
        simulateRepairCompleted(fixture, "entity-1:answer");
        conflictRepairService.advance(fixture.runId());

        ResearchAgentRunCompletionGate.CompletionDecision decision = gate.evaluateForRun(fixture.runId());

        assertThat(decision.terminalState())
                .isEqualTo(ResearchAgentRunCompletionGate.CompletionTerminalState.INSUFFICIENT_EVIDENCE);
        assertThat(decision.reasonCodes()).contains(
                "EVIDENCE_CONFLICT", "CONFLICT_REPAIR_EXHAUSTED", "CONFLICTED_CELL");
        assertThat(decision.limitations()).contains("EVIDENCE_CONFLICT", "CONFLICT_REPAIR_EXHAUSTED");
        assertThat(decision.unresolvedCells()).singleElement().satisfies(unresolved -> {
            assertThat(unresolved.cellKey()).isEqualTo("entity-1:answer");
            assertThat(unresolved.reasonCode()).isEqualTo("CONFLICT_REPAIR_EXHAUSTED");
        });
        assertThat(decision.promotableClaims()).isEmpty();
    }

    // ------------------------------------------------------------------ 5. no runaway

    @Test
    void shouldNotCreateUnboundedRepairTasksWhenTheConflictPersists() {
        Fixture fixture = seedConflict();

        for (int tick = 0; tick < 5; tick++) {
            conflictRepairService.advance(fixture.runId());
        }
        assertThat(conflictRepairTasks(fixture)).isEqualTo(1);

        simulateRepairCompleted(fixture, "entity-1:answer");
        for (int tick = 0; tick < 5; tick++) {
            conflictRepairService.advance(fixture.runId());
            conflictService.adjudicateRun(fixture.runId());
        }

        assertThat(conflictRepairTasks(fixture)).isEqualTo(1);
        ResearchAgentConflictRepairService.ConflictRepairLedger ledger =
                conflictRepairService.conflictRepairLedger(fixture.runId());
        assertThat(ledger.attempts()).isEqualTo(1);
        assertThat(ledger.attemptUpperBound()).isEqualTo(ResearchAgentConflictRepairService.MAX_CONFLICT_REPAIR_PER_CELL);
        assertThat(cellStatus(fixture, "entity-1:answer")).isEqualTo("CONFLICT_EXHAUSTED");
    }

    // ------------------------------------------------------------------ 6. call ledger

    @Test
    void shouldAnswerTheConflictRepairCallLedger() {
        Fixture fixture = seedConflict();
        conflictRepairService.advance(fixture.runId());
        // Simulate the settled budget of the dispatched attempt.
        jdbcTemplate.update("""
                update research_budget_reservation set consumed_json = reserved_json
                where research_agent_task_id in (
                    select id from research_agent_task
                    where research_run_id = ? and logical_task_key like 'conflict-counterfactual:%')
                """, fixture.runId());

        ResearchAgentConflictRepairService.ConflictRepairLedger ledger =
                conflictRepairService.conflictRepairLedger(fixture.runId());

        assertThat(ledger.attempts()).isEqualTo(1);
        assertThat(ledger.externalCallsSpent()).isEqualTo(9L);
        assertThat(ledger.externalCallsUpperBound())
                .isEqualTo(ResearchAgentConflictRepairService.EXTERNAL_CALLS_PER_CONFLICT_TASK);
    }

    // ------------------------------------------------------------------ fixture plumbing

    private Fixture seedConflict() {
        Fixture fixture = seedRun();
        seedCell(fixture, "entity-1:answer", "answer");
        bindConflict(fixture, "ev-a", "alpha.com", LINEAGE_A, "revenue was 100");
        bindConflict(fixture, "ev-b", "beta.org", LINEAGE_B, "revenue was 200");
        assertThat(conflictService.adjudicateRun(fixture.runId())).isEqualTo(1);
        return fixture;
    }

    private String cellStatus(Fixture fixture, String cellKey) {
        return jdbcTemplate.queryForObject(
                "select cell_status from research_cell where research_run_id = ? and cell_key = ?",
                String.class, fixture.runId(), cellKey);
    }

    private int conflictRepairTasks(Fixture fixture) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task
                where research_run_id = ? and logical_task_key like 'conflict-counterfactual:%'
                """, Integer.class, fixture.runId());
        return count == null ? 0 : count;
    }

    /** Simulates the worker hand-off: the repair task terminates and the cell binding is released. */
    private void simulateRepairCompleted(Fixture fixture, String cellKey) {
        jdbcTemplate.update("""
                update research_agent_task set status = 'SUBMITTED', terminal_at = current_timestamp
                where research_run_id = ? and logical_task_key like 'conflict-counterfactual:%'
                """, fixture.runId());
        jdbcTemplate.update(
                "update research_cell set active_task_id = null where research_run_id = ? and cell_key = ?",
                fixture.runId(), cellKey);
    }

    private Fixture seedRun() {
        String workspaceId = Ids.newId();
        String parentTaskId = Ids.newId();
        String runId = Ids.newId();
        String rowId = Ids.newId();
        String fileId = Ids.newId();
        String sourceId = Ids.newId();
        String snapshotId = Ids.newId();
        String chunkId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')",
                workspaceId, "dr304-" + runId);
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, parentTaskId, workspaceId, runId);
        jdbcTemplate.update("insert into file_object(id, workspace_id, object_key, sha256, file_size) values (?, ?, ?, ?, 20)",
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
        jdbcTemplate.update("""
                insert into research_run(
                    id, workspace_id, task_id, question, profile_key, source_scope_json,
                    research_intent_json, control_pack_json, retrieval_mode, status, agent_execution_mode)
                values (?, ?, ?, 'dr304 question', 'DEFAULT', ?, ?, ?, 'WEB_PLUS_SEEDS', 'RUNNING', 'INCREMENTAL_V1')
                """, runId, workspaceId, parentTaskId, objectMapperJson(List.of(sourceId)),
                objectMapperJson(Map.of("research_goal", "dr304", "deliverable_format", "memo",
                        "constraints", List.of("auditable"), "time_range", "2024-2026", "depth", "DEEP",
                        "research_type", "TECHNICAL")),
                objectMapperJson(Map.of("pack_type", "RESEARCH_AGENT", "target_key", "DEFAULT",
                        "task_neighborhood", "RESEARCH_DEFAULT", "style_constraints", List.of("concise"),
                        "structure_constraints", List.of("limitations"), "terminology_policy", List.of("canonical"),
                        "forbidden_patterns", List.of("unsupported"), "evidence_policy", List.of("snapshot-only"))));
        jdbcTemplate.update(
                "insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'CANDIDATE_READY')",
                rowId, runId);
        return new Fixture(runId, rowId);
    }

    private void seedCell(Fixture fixture, String cellKey, String columnKey) {
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key,
                    candidate_value, cell_status, repair_count, cell_version, plan_revision, entity_set_version)
                values (?, ?, ?, ?, ?, '', 'CANDIDATE_READY', 0, 0, 1, 1)
                """, Ids.newId(), fixture.runId(), fixture.rowId(), cellKey, columnKey);
    }

    private void bindConflict(Fixture fixture, String evidenceKey, String domain, String lineage, String claim) {
        bindEvidence(fixture, "entity-1:answer", evidenceKey, domain, lineage, claim);
    }

    private void bindEvidence(Fixture fixture, String cellKey, String evidenceKey, String domain,
                              String lineage, String claim) {
        String evidenceId = Ids.newId();
        jdbcTemplate.update("""
                insert into source_evidence(id, research_run_id, evidence_key, source_id, relation_type,
                    claim_text, quote_text, source_domain, lineage_digest, snapshot_status)
                values (?, ?, ?, ?, 'SUPPORTS', ?, ?, ?, ?, 'WORKSPACE')
                """, evidenceId, fixture.runId(), evidenceKey, "evidence-source-" + evidenceKey,
                claim, claim, domain, lineage);
        jdbcTemplate.update("""
                insert into research_cell_evidence(id, research_run_id, research_cell_id, source_evidence_id, evidence_key)
                values (?, ?, (select id from research_cell where research_run_id = ? and cell_key = ?), ?, ?)
                """, Ids.newId(), fixture.runId(), fixture.runId(), cellKey, evidenceId, evidenceKey);
    }

    private String objectMapperJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize fixture JSON", exception);
        }
    }

    private record Fixture(String runId, String rowId) { }
}
