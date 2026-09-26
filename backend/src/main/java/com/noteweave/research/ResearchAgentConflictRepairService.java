package com.noteweave.research;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DR-304: bounded counterfactual repair for DR-303 global conflicts.
 *
 * <p>DR-303 makes a conflict {@code CONFLICTED} (and sticky). This service is the "how it is handled"
 * half: it turns each <b>open</b> conflict into at most one cell-scoped counterfactual repair task
 * that carries the conflict trace id and the involved evidence ids, and — when the bounded budget is
 * spent without resolution — records that explicitly instead of stopping silently.</p>
 *
 * <h2>Independent metering (no {@code WORKER_USAGE_KEYS} change)</h2>
 *
 * <p>A conflict repair is a {@code COUNTERFACTUAL} task whose {@code logical_task_key} carries the
 * dedicated prefix {@value #CONFLICT_TASK_LOGICAL_KEY_PREFIX}. That prefix is the independent counter:
 * quorum/failed-wave repairs keep the plain {@code counterfactual:} prefix, so the two are separable
 * by a single query and the attempt count is never mixed into {@code research_cell.repair_count}.</p>
 *
 * <h2>Unbypassable bound</h2>
 *
 * <p>The bound is {@value #MAX_CONFLICT_REPAIR_PER_CELL} attempt per cell, enforced against the
 * <em>persisted</em> task rows. A dispatch always writes one task row, and the count is derived from
 * those rows, so the cap cannot be exceeded by re-entering the tick: once the count reaches the cap
 * the only transition left is the explicit exhaustion record. The general
 * {@code ResearchAgentRepairStopPolicy} / {@code MAX_REPAIR_PER_CELL} budget is still applied by
 * {@link ResearchAgentTaskCoordinatorService#planCounterfactualRepairs} (it increments
 * {@code repair_count}), so this is bounded from both sides.</p>
 *
 * <h2>Call ledger</h2>
 *
 * <p>{@link #conflictRepairLedger(String)} derives "how many external calls did this Run spend on
 * counterfactual repair, and what is the upper bound" from the already-persisted task rows and their
 * budget reservations — no new counter key is introduced.</p>
 */
@Service
class ResearchAgentConflictRepairService {

    /** At most one counterfactual attempt per cell per conflict. */
    static final int MAX_CONFLICT_REPAIR_PER_CELL = 1;
    /** Independent logical-key namespace for conflict repairs (the metering discriminator). */
    static final String CONFLICT_TASK_LOGICAL_KEY_PREFIX = "conflict-counterfactual:";
    /** Trace {@code decision_type} of the explicit exhaustion marker row. */
    static final String EXHAUSTED_DECISION_TYPE = "GLOBAL_CONFLICT_REPAIR_EXHAUSTED";
    /** External call dimensions reserved per conflict task by {@code deepCellBudget(1)}. */
    static final long EXTERNAL_CALLS_PER_CONFLICT_TASK = 9L;
    private static final Set<String> EXTERNAL_CALL_KEYS = Set.of("search_calls", "fetch_calls", "read_calls");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentTaskCoordinatorService coordinator;

    ResearchAgentConflictRepairService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentTaskCoordinatorService coordinator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.coordinator = coordinator;
    }

    /**
     * Advances every open conflict: dispatch one bounded conflict repair, or record the explicit
     * exhaustion once the cap is reached. Idempotent and safe to call on every coordinator tick.
     */
    @Transactional
    public ConflictRepairReceipt advance(String runId) {
        List<ConflictTrace> traces = jdbcTemplate.query("""
                select id, target_id, reason_code, evidence_ids_json
                from research_verifier_decision
                where research_run_id = ? and decision_scope = 'CELL' and decision_type = ?
                  and decision_status = 'OPEN' and target_id is not null
                order by cast(id as binary)
                """, (rs, rowNum) -> new ConflictTrace(
                rs.getString("id"), rs.getString("target_id"), rs.getString("reason_code"),
                rs.getString("evidence_ids_json")), runId, ResearchGlobalConflictService.CONFLICT_DECISION_TYPE);
        if (traces.isEmpty()) return new ConflictRepairReceipt(0, 0, 0);
        String mode = runMode(runId);
        boolean runnable = "INCREMENTAL_V1".equals(mode) && "RUNNING".equals(runStatus(runId));
        int dispatched = 0;
        int exhausted = 0;
        for (ConflictTrace trace : traces) {
            CellRow cell = loadCell(runId, trace.cellKey());
            if (cell == null || "FROZEN".equals(cell.status())) continue;
            if (cell.activeTaskId() != null) continue; // a repair is already in flight
            int attempts = conflictRepairAttempts(runId, trace.cellKey());
            if (attempts >= MAX_CONFLICT_REPAIR_PER_CELL) {
                markExhausted(runId, trace, cell, attempts);
                exhausted++;
            } else if (runnable && coordinator.counterfactualRepairFeasible(runId)) {
                dispatch(runId, cell, trace);
                dispatched++;
            }
        }
        return new ConflictRepairReceipt(traces.size(), dispatched, exhausted);
    }

    // ------------------------------------------------------------------ dispatch

    private void dispatch(String runId, CellRow cell, ConflictTrace trace) {
        List<String> evidenceKeys = readStringList(trace.evidenceIdsJson());
        List<String> excluded = sourceIds(runId, evidenceKeys);
        int wave = integer("select coalesce(max(wave_no), 1) from research_agent_task where research_run_id = ?", runId);
        int checkpoint = integer("select coalesce(max(checkpoint_seq), 0) from research_agent_checkpoint where research_run_id = ?", runId);
        coordinator.planCounterfactualRepairs(new ResearchAgentTaskCoordinatorService.CounterfactualRepairCommand(
                runId, checkpoint, Math.max(1, wave + 1),
                List.of(new ResearchAgentTaskCoordinatorService.CounterfactualTarget(
                        cell.cellKey(), reasonDigest(cell.cellKey(), trace.id(), evidenceKeys), excluded,
                        trace.id(), evidenceKeys))));
    }

    /**
     * The conflict-repair attempt count: the number of persisted conflict tasks for the cell. This is
     * the independent meter — it is never {@code research_cell.repair_count}.
     */
    int conflictRepairAttempts(String runId, String cellKey) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task
                where research_run_id = ? and logical_task_key like ? and target_cells_json like ?
                """, Integer.class, runId, CONFLICT_TASK_LOGICAL_KEY_PREFIX + "%", "%\"" + cellKey + "\"%");
        return count == null ? 0 : count;
    }

    private void markExhausted(String runId, ConflictTrace trace, CellRow cell, int attempts) {
        // 1. the conflict trace itself leaves OPEN and enters the explicit terminal state.
        jdbcTemplate.update("""
                update research_verifier_decision set decision_status = 'EXHAUSTED'
                where research_run_id = ? and decision_scope = 'CELL' and decision_type = ?
                  and target_id = ? and decision_status = 'OPEN'
                """, runId, ResearchGlobalConflictService.CONFLICT_DECISION_TYPE, cell.cellKey());
        // 2. a stable, idempotent marker row recording the bound that was reached.
        Integer marker = jdbcTemplate.queryForObject("""
                select count(*) from research_verifier_decision
                where research_run_id = ? and decision_type = ? and target_id = ?
                """, Integer.class, runId, EXHAUSTED_DECISION_TYPE, cell.cellKey());
        if (marker == null || marker == 0) {
            Map<String, Object> notes = new LinkedHashMap<>();
            notes.put("cell_key", cell.cellKey());
            notes.put("attempts", attempts);
            notes.put("attempt_upper_bound", MAX_CONFLICT_REPAIR_PER_CELL);
            notes.put("conflict_trace_id", trace.id());
            jdbcTemplate.update("""
                    insert into research_verifier_decision(
                        id, research_run_id, decision_scope, decision_type, reason_code,
                        target_id, evidence_ids_json, action_text, decision_status, notes_json)
                    values (?, ?, 'CELL', ?, ?, ?, ?, ?, 'EXHAUSTED', ?)
                    """, Ids.newId(), runId, EXHAUSTED_DECISION_TYPE,
                    ResearchAgentRunCompletionGate.REASON_CONFLICT_REPAIR_EXHAUSTED, cell.cellKey(),
                    trace.evidenceIdsJson(), "conflict_repair_attempts=" + attempts
                            + ":upper_bound=" + MAX_CONFLICT_REPAIR_PER_CELL,
                    Json.write(objectMapper, notes));
        }
        // 3. the cell enters the explicit exhausted state so the gate can report a distinct cause.
        jdbcTemplate.update("""
                update research_cell set cell_status = ?, updated_at = current_timestamp
                where research_run_id = ? and cell_key = ? and cell_status = ?
                """, ResearchGlobalConflictService.CONFLICT_EXHAUSTED_CELL_STATUS, runId,
                cell.cellKey(), ResearchGlobalConflictService.CONFLICTED_CELL_STATUS);
    }

    // ------------------------------------------------------------------ call ledger

    /**
     * Answers "how many external calls did this Run spend on counterfactual repair, and what is the
     * upper bound" from already-persisted rows: the conflict tasks, their budget reservations, and the
     * number of conflicted cells. No new usage counter is added.
     */
    ConflictRepairLedger conflictRepairLedger(String runId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select t.id as task_id, r.reserved_json as reserved_json, r.consumed_json as consumed_json
                from research_agent_task t
                left join research_budget_reservation r on r.research_agent_task_id = t.id
                where t.research_run_id = ? and t.logical_task_key like ?
                order by t.id
                """, runId, CONFLICT_TASK_LOGICAL_KEY_PREFIX + "%");
        long spent = 0L;
        long allocated = 0L;
        for (Map<String, Object> row : rows) {
            spent += externalCalls(string(row.get("consumed_json")));
            allocated += externalCalls(string(row.get("reserved_json")));
        }
        int conflictCells = conflictCellCount(runId);
        int attemptUpperBound = conflictCells * MAX_CONFLICT_REPAIR_PER_CELL;
        long externalCallsUpperBound = attemptUpperBound * EXTERNAL_CALLS_PER_CONFLICT_TASK;
        return new ConflictRepairLedger(runId, rows.size(), attemptUpperBound, spent, allocated, externalCallsUpperBound);
    }

    private int conflictCellCount(String runId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(distinct target_id) from research_verifier_decision
                where research_run_id = ? and decision_type = ? and target_id is not null
                """, Integer.class, runId, ResearchGlobalConflictService.CONFLICT_DECISION_TYPE);
        return count == null ? 0 : count;
    }

    private long externalCalls(String json) {
        if (json == null || json.isBlank()) return 0L;
        try {
            Map<String, Object> values = objectMapper.readValue(json, new TypeReference<>() { });
            long total = 0L;
            for (String key : EXTERNAL_CALL_KEYS) {
                Object value = values.get(key);
                if (value instanceof Number number) total += number.longValue();
            }
            return total;
        } catch (Exception exception) {
            throw new BusinessException("RESEARCH_AGENT_CONFLICT_LEDGER_INVALID", "Conflict budget ledger is not readable");
        }
    }

    // ------------------------------------------------------------------ helpers

    private CellRow loadCell(String runId, String cellKey) {
        return jdbcTemplate.query("""
                select cell_key, cell_status, active_task_id from research_cell
                where research_run_id = ? and cell_key = ?
                """, rs -> rs.next() ? new CellRow(
                rs.getString("cell_key"), rs.getString("cell_status"), rs.getString("active_task_id")) : null,
                runId, cellKey);
    }

    private List<String> sourceIds(String runId, List<String> evidenceKeys) {
        if (evidenceKeys.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(evidenceKeys.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(runId);
        parameters.addAll(evidenceKeys);
        return List.copyOf(jdbcTemplate.query("""
                select distinct source_id from source_evidence
                where research_run_id = ? and evidence_key in (""" + placeholders + ") and source_id is not null order by source_id",
                (rs, rowNum) -> rs.getString(1), parameters.toArray()));
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() { });
            if (values == null) return List.of();
            return values.stream().filter(value -> value != null && !value.isBlank()).distinct().sorted().toList();
        } catch (Exception exception) {
            throw new BusinessException("RESEARCH_AGENT_CONFLICT_TRACE_INVALID", "Conflict trace evidence list is invalid");
        }
    }

    private String reasonDigest(String cellKey, String traceId, List<String> evidenceKeys) {
        try {
            String text = "conflict-repair|" + cellKey + "|trace@" + traceId + "|evidence@"
                    + String.join(",", evidenceKeys);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder("sha256:");
            for (byte value : hash) output.append(String.format("%02x", value));
            return output.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot digest conflict repair reason", exception);
        }
    }

    private String runMode(String runId) {
        return jdbcTemplate.query("select agent_execution_mode from research_run where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, runId);
    }

    private String runStatus(String runId) {
        return jdbcTemplate.query("select status from research_run where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, runId);
    }

    private int integer(String sql, String runId) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, runId);
        return value == null ? 0 : value;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    record ConflictRepairReceipt(int openConflicts, int dispatched, int exhausted) { }

    record ConflictRepairLedger(String runId, int attempts, int attemptUpperBound,
                                long externalCallsSpent, long externalCallsAllocated,
                                long externalCallsUpperBound) { }

    private record ConflictTrace(String id, String cellKey, String reasonCode, String evidenceIdsJson) { }

    private record CellRow(String cellKey, String status, String activeTaskId) { }
}
