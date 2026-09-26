package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DR-303 / DR-304: the canonical-ledger side of global conflict handling.
 *
 * <p>The judgement itself lives in {@link ResearchGlobalConflictDetector} (a pure function over
 * persisted facts). This service is the only place that (a) loads those facts from the ledger,
 * (b) persists the "conflict trace" and (c) makes the conflict explicit at the cell layer so
 * {@link ResearchAgentRunCompletionGate} can discriminate it from "no evidence was ever obtained".</p>
 *
 * <h2>Storage choice</h2>
 *
 * <p>A conflict is written as {@code research_cell.cell_status = 'CONFLICTED'} plus one
 * {@code research_verifier_decision} row per granular reason code with
 * {@code decision_type = 'GLOBAL_CONFLICT'}. Rationale:</p>
 *
 * <ul>
 *   <li>{@code cell_status} is already the exact input the terminal gate is a pure function of, and
 *       {@code CellState#resolved()} already excludes anything that is not {@code VERIFIED} — so a
 *       conflict cell can never be silently promoted while keeping its old value.</li>
 *   <li>{@code research_cell.cell_status} has no CHECK constraint and {@code 'CONFLICTED'} is
 *       already the vocabulary the legacy Worker and the Java read models use for
 *       {@code research_row.row_status}; this is not a new cross-language word.</li>
 *   <li>{@code research_verifier_decision} already carries every field a conflict trace needs
 *       ({@code research_run_id}, {@code target_id} = cell key, {@code reason_code}, bounded
 *       {@code action_text}) and matches the existing convention "decision_type = coarse category,
 *       reason_code = granular cause" used by the quorum-repair and evidence-audit writers, so no new
 *       table or migration is required.</li>
 * </ul>
 *
 * <h2>DR-304 lifecycle (additive; the DR-303 predicate is untouched)</h2>
 *
 * <p>DR-303 left the conflict <em>sticky</em>. DR-304 removes that by letting this re-adjudication
 * also <b>clear</b> a conflict that has been resolved ({@link ResearchAgentConflictResolutionPolicy})
 * and by <b>preserving</b> an exhausted conflict so a later tick cannot silently reopen it:</p>
 *
 * <ul>
 *   <li>live &amp; unresolved → cell {@code CONFLICTED}, trace {@code OPEN} (reopened if it had been
 *       resolved earlier);</li>
 *   <li>live &amp; already exhausted → cell {@code CONFLICT_EXHAUSTED} (the repair budget is spent),
 *       trace stays {@code EXHAUSTED};</li>
 *   <li>resolved or gone → cell leaves the conflict status back to a promotable state, trace
 *       {@code RESOLVED}.</li>
 * </ul>
 */
@Service
class ResearchGlobalConflictService {

    /** Cell status for a cell whose cross-source evidence is mutually exclusive. */
    static final String CONFLICTED_CELL_STATUS = "CONFLICTED";
    /** Cell status for a conflict whose bounded counterfactual repair did not resolve it. */
    static final String CONFLICT_EXHAUSTED_CELL_STATUS = "CONFLICT_EXHAUSTED";
    /** Trace {@code decision_type} used for global conflicts. */
    static final String CONFLICT_DECISION_TYPE = "GLOBAL_CONFLICT";

    static final String TRACE_OPEN = "OPEN";
    static final String TRACE_EXHAUSTED = "EXHAUSTED";
    static final String TRACE_RESOLVED = "RESOLVED";

    /** A resolved conflict must become promotable again, so the cell re-enters the research set. */
    static final String RESOLVED_CELL_FALLBACK_STATUS = "CANDIDATE_READY";

    private static final String CONFLICT_MARKER_PREFIX = "GLOBAL_CONFLICT:";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchGlobalConflictDetector detector;
    private final ResearchAgentConflictResolutionPolicy resolutionPolicy;

    ResearchGlobalConflictService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchGlobalConflictDetector detector,
            ResearchAgentConflictResolutionPolicy resolutionPolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.detector = detector;
        this.resolutionPolicy = resolutionPolicy;
    }

    /**
     * Re-adjudicates every non-frozen cell of a Run against the current canonical facts and persists
     * the resulting conflicts. Idempotent: a conflict that was already recorded for the same cell and
     * kind is not duplicated, and re-demoting an already conflicted cell is a no-op.
     *
     * @return the number of cells for which a <b>live, unresolved</b> conflict currently holds
     */
    @Transactional
    public int adjudicateRun(String runId) {
        // DR-304 lock order: take the run row lock before touching any cell row, so this method — and
        // the coordinator tick that calls it first — uses the same run -> cell order as
        // ResearchAgentCompletionCommitter.lockRun/lockCells. Without it the tick would take cell locks
        // here and only later need the run lock (snapshot/dispatch), which is an ABBA window against a
        // concurrent completion commit. The SELECT FOR UPDATE is a no-op (no row) for an unknown run,
        // so every existing caller, including the DR-303 unit tests that call this directly, keeps its
        // outcome; they merely acquire the run lock earlier.
        jdbcTemplate.query("select id from research_run where id = ? for update",
                (rs, rowNum) -> rs.getString(1), runId);
        List<CellFactRow> cells = jdbcTemplate.query("""
                select id, cell_key, cell_status, candidate_value
                from research_cell
                where research_run_id = ? and cell_status <> 'FROZEN'
                order by cell_key
                """, (rs, rowNum) -> new CellFactRow(
                rs.getString("id"), rs.getString("cell_key"), rs.getString("cell_status"),
                rs.getString("candidate_value")), runId);
        int conflicts = 0;
        for (CellFactRow cell : cells) {
            ResearchGlobalConflictDetector.CellConflictFacts facts = facts(runId, cell);
            ResearchGlobalConflictDetector.ConflictFinding finding = detector.detectCell(facts);
            boolean resolved = finding == null || resolutionPolicy.isResolved(facts, finding);
            String traceStatus = currentTraceStatus(runId, cell.cellKey());
            if (resolved) {
                resolveConflict(runId, cell, traceStatus);
            } else {
                conflicts++;
                persistConflict(runId, cell, finding, traceStatus);
            }
        }
        return conflicts;
    }

    /** Canonical facts for one cell: its persisted state plus every evidence fact bound to it. */
    ResearchGlobalConflictDetector.CellConflictFacts facts(String runId, String cellKey) {
        List<CellFactRow> rows = jdbcTemplate.query("""
                select id, cell_status, candidate_value from research_cell
                where research_run_id = ? and cell_key = ?
                """, (rs, rowNum) -> new CellFactRow(
                rs.getString("id"), cellKey, rs.getString("cell_status"),
                rs.getString("candidate_value")), runId, cellKey);
        if (rows.isEmpty()) return null;
        CellFactRow row = rows.get(0);
        return new ResearchGlobalConflictDetector.CellConflictFacts(
                row.cellKey(), row.status(), row.value(), loadEvidence(runId, row.id()));
    }

    private ResearchGlobalConflictDetector.CellConflictFacts facts(String runId, CellFactRow cell) {
        return new ResearchGlobalConflictDetector.CellConflictFacts(
                cell.cellKey(), cell.status(), cell.value(), loadEvidence(runId, cell.id()));
    }

    /**
     * Every evidence fact associated with one cell. Association is the union of accepted evidence
     * lineage ({@code research_cell_evidence}) and candidate-bound evidence
     * ({@code research_evidence_validation.cell_id}); the latter is the only link that can carry a
     * {@code CONFLICTS} relation, which a promoted candidate never has.
     */
    List<ResearchGlobalConflictDetector.EvidenceFact> loadEvidence(String runId, String cellId) {
        return jdbcTemplate.query("""
                select se.evidence_key, se.relation_type, se.claim_text, se.quote_text,
                       se.source_domain, se.lineage_digest
                from source_evidence se
                where se.research_run_id = ?
                  and (exists (
                        select 1 from research_evidence_validation rev
                        where rev.research_run_id = se.research_run_id
                          and rev.evidence_id = se.id and rev.cell_id = ?
                  ) or exists (
                        select 1 from research_cell_evidence rce
                        where rce.research_run_id = se.research_run_id
                          and rce.source_evidence_id = se.id and rce.research_cell_id = ?
                  ))
                order by se.evidence_key
                """, (rs, rowNum) -> new ResearchGlobalConflictDetector.EvidenceFact(
                rs.getString("evidence_key"), rs.getString("relation_type"), rs.getString("claim_text"),
                rs.getString("quote_text"), rs.getString("source_domain"),
                rs.getString("lineage_digest")), runId, cellId, cellId);
    }

    /**
     * Aggregate trace state for a cell: a spent repair budget dominates a resolved row, and "all rows
     * resolved" is the only way to read RESOLVED. Any other mix is still a live conflict.
     */
    private String currentTraceStatus(String runId, String cellKey) {
        List<String> statuses = jdbcTemplate.queryForList("""
                select decision_status from research_verifier_decision
                where research_run_id = ? and decision_scope = 'CELL' and decision_type = ?
                  and target_id = ?
                """, String.class, runId, CONFLICT_DECISION_TYPE, cellKey);
        if (statuses.isEmpty()) return null;
        if (statuses.contains(TRACE_EXHAUSTED)) return TRACE_EXHAUSTED;
        return statuses.stream().allMatch(TRACE_RESOLVED::equals) ? TRACE_RESOLVED : TRACE_OPEN;
    }

    private void persistConflict(
            String runId,
            CellFactRow cell,
            ResearchGlobalConflictDetector.ConflictFinding finding,
            String traceStatus
    ) {
        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("cell_key", cell.cellKey());
        notes.put("conflict_kind", finding.conflictKind());
        notes.put("evidence_count", finding.evidenceKeys().size());
        // One trace row per granular reason code, so the ledger can be queried by the exact cause
        // (NUMBER_VALUE_CONFLICT / COMPARISON_DIRECTION_CONFLICT / CONFLICTS_RELATION_WITH_SUPPORT)
        // while notes_json keeps the coarse shape kind.
        for (String reasonCode : finding.reasonCodes()) {
            Integer existing = jdbcTemplate.queryForObject("""
                    select count(*) from research_verifier_decision
                    where research_run_id = ? and decision_scope = 'CELL' and decision_type = ?
                      and target_id = ? and reason_code = ?
                    """, Integer.class, runId, CONFLICT_DECISION_TYPE, cell.cellKey(), reasonCode);
            if (existing != null && existing > 0) continue;
            jdbcTemplate.update("""
                    insert into research_verifier_decision(
                        id, research_run_id, decision_scope, decision_type, reason_code,
                        target_id, evidence_ids_json, action_text, decision_status, notes_json)
                    values (?, ?, 'CELL', ?, ?, ?, ?, ?, 'OPEN', ?)
                    """, Ids.newId(), runId, CONFLICT_DECISION_TYPE, reasonCode,
                    cell.cellKey(), Json.write(objectMapper, finding.evidenceKeys()),
                    finding.rationale(), Json.write(objectMapper, notes));
        }
        // A conflict that had been resolved and now fires again is a live conflict: reopen its trace.
        // A row that is EXHAUSTED is deliberately left alone (the repair budget is spent).
        if (TRACE_RESOLVED.equals(traceStatus)) {
            jdbcTemplate.update("""
                    update research_verifier_decision set decision_status = 'OPEN'
                    where research_run_id = ? and decision_scope = 'CELL' and decision_type = ?
                      and target_id = ? and decision_status = 'RESOLVED'
                    """, runId, CONFLICT_DECISION_TYPE, cell.cellKey());
        }
        // Make the conflict explicit at the cell layer. The previous candidate_value is deliberately
        // kept for audit, but the cell is no longer VERIFIED, so it can never be promoted as-is.
        // An exhausted conflict keeps its terminal status instead of silently returning to CONFLICTED.
        String targetStatus = TRACE_EXHAUSTED.equals(traceStatus)
                ? CONFLICT_EXHAUSTED_CELL_STATUS : CONFLICTED_CELL_STATUS;
        jdbcTemplate.update("""
                update research_cell
                set cell_status = ?, last_verifier_decision = ?, updated_at = current_timestamp
                where id = ? and research_run_id = ? and cell_status <> 'FROZEN' and cell_status <> ?
                """, targetStatus, CONFLICT_MARKER_PREFIX + finding.conflictKind(),
                cell.id(), runId, targetStatus);
    }

    /** A resolved (or vanished) conflict leaves the cell promotable again and closes its trace. */
    private void resolveConflict(String runId, CellFactRow cell, String traceStatus) {
        if (TRACE_OPEN.equals(traceStatus) || TRACE_EXHAUSTED.equals(traceStatus)) {
            jdbcTemplate.update("""
                    update research_verifier_decision set decision_status = 'RESOLVED'
                    where research_run_id = ? and decision_scope = 'CELL' and decision_type = ?
                      and target_id = ? and decision_status in ('OPEN', 'EXHAUSTED')
                    """, runId, CONFLICT_DECISION_TYPE, cell.cellKey());
        }
        if (CONFLICTED_CELL_STATUS.equals(cell.status())
                || CONFLICT_EXHAUSTED_CELL_STATUS.equals(cell.status())) {
            jdbcTemplate.update("""
                    update research_cell
                    set cell_status = ?, updated_at = current_timestamp
                    where id = ? and research_run_id = ?
                      and cell_status in ('CONFLICTED', 'CONFLICT_EXHAUSTED')
                    """, RESOLVED_CELL_FALLBACK_STATUS, cell.id(), runId);
        }
    }

    private record CellFactRow(String id, String cellKey, String status, String value) { }
}
