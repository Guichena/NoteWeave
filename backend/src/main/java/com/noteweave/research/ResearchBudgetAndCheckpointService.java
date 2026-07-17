package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MA3C durable budget conservation and coordinator-owned checkpoint sequencing. */
@Service
public class ResearchBudgetAndCheckpointService {

    private static final String ATOMIC_SNAPSHOT_SCHEMA_V1 = "research-agent-task-snapshot.v1";
    private static final String ATOMIC_SNAPSHOT_SCHEMA_V2 = "research-agent-task-snapshot.v2";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ResearchBudgetAndCheckpointService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReservationReceipt reserve(ReserveCommand command) {
        requireNonNegative(command.reserved());
        ReservationRow existing = findByIdempotency(command.researchRunId(), command.idempotencyKey());
        if (existing != null) {
            if (!existing.taskId().equals(command.taskId()) || !existing.reserved().equals(normalize(command.reserved()))) {
                throw new BusinessException("RESEARCH_BUDGET_IDEMPOTENCY_CONFLICT", "Budget idempotency key is bound to different content");
            }
            return new ReservationReceipt(existing.id(), true);
        }
        requireReservableTask(command.researchRunId(), command.taskId());
        String id = Ids.newId();
        Map<String, Long> zero = zeroLike(command.reserved());
        try {
            jdbcTemplate.update("""
                    insert into research_budget_reservation(
                        id, research_run_id, research_agent_task_id, idempotency_key,
                        reserved_json, consumed_json, released_json, state
                    ) values (?, ?, ?, ?, ?, ?, ?, 'RESERVED')
                    """, id, command.researchRunId(), command.taskId(), command.idempotencyKey(),
                    Json.write(objectMapper, normalize(command.reserved())), Json.write(objectMapper, zero), Json.write(objectMapper, zero));
            return new ReservationReceipt(id, false);
        } catch (DataIntegrityViolationException duplicate) {
            ReservationRow replay = findByIdempotency(command.researchRunId(), command.idempotencyKey());
            if (replay != null && replay.taskId().equals(command.taskId()) && replay.reserved().equals(normalize(command.reserved()))) {
                return new ReservationReceipt(replay.id(), true);
            }
            throw duplicate;
        }
    }

    @Transactional
    public BudgetSnapshot settle(SettleCommand command) {
        ReservationRow row = lockDirectMutationScope(command.reservationId());
        requireNonNegative(command.consumed());
        if (!"RESERVED".equals(row.state())) {
            throw new BusinessException("RESEARCH_BUDGET_NOT_SETTLEABLE", "Budget reservation is already terminal");
        }
        Map<String, Long> consumed = normalize(command.consumed());
        if (!within(consumed, row.reserved())) {
            throw new BusinessException("RESEARCH_BUDGET_EXCEEDED", "Consumed budget exceeds reservation");
        }
        Map<String, Long> released = subtract(row.reserved(), consumed);
        int updated = jdbcTemplate.update("""
                update research_budget_reservation
                set consumed_json = ?, released_json = ?, state = 'SETTLED',
                    settled_at = current_timestamp, updated_at = current_timestamp
                where id = ? and state = 'RESERVED'
                """, Json.write(objectMapper, consumed), Json.write(objectMapper, released), row.id());
        if (updated != 1) throw new BusinessException("RESEARCH_BUDGET_NOT_SETTLEABLE", "Budget reservation lost settlement race");
        return new BudgetSnapshot(row.reserved(), consumed, released, "SETTLED");
    }

    /** Settles only when the task has a reservation; legacy sequential tasks remain unaffected. */
    @Transactional
    public BudgetSnapshot settleForExecution(String runId, String taskId, String executionKey, Map<String, Long> consumed) {
        Integer executionCount = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_execution e join research_agent_task t on t.id = e.research_agent_task_id
                where e.research_agent_task_id = ? and e.execution_key = ? and t.research_run_id = ?
                """, Integer.class, taskId, executionKey, runId);
        if (executionCount == null || executionCount != 1) {
            throw new BusinessException("RESEARCH_BUDGET_EXECUTION_NOT_FOUND", "Execution does not exist for task");
        }
        ReservationRow row = jdbcTemplate.query("""
                select id, research_run_id, research_agent_task_id, idempotency_key, reserved_json, consumed_json, released_json, state
                from research_budget_reservation where research_agent_task_id = ?
                """, rs -> rs.next() ? mapReservation(rs) : null, taskId);
        if (row == null) return null;
        if (!row.runId().equals(runId)) throw new BusinessException("RESEARCH_BUDGET_TASK_NOT_FOUND", "Reservation run mismatch");
        Map<String, Long> normalized = normalize(consumed);
        if ("SETTLED".equals(row.state()) && row.consumed().equals(normalized)) {
            return new BudgetSnapshot(row.reserved(), row.consumed(), row.released(), row.state());
        }
        if (!"RESERVED".equals(row.state())) {
            throw new BusinessException("RESEARCH_BUDGET_NOT_SETTLEABLE", "Budget reservation is already terminal");
        }
        return settle(new SettleCommand(row.id(), normalized));
    }

    @Transactional
    public BudgetSnapshot release(String reservationId) {
        ReservationRow row = lockDirectMutationScope(reservationId);
        if ("RELEASED".equals(row.state())) return new BudgetSnapshot(row.reserved(), row.consumed(), row.released(), row.state());
        if ("SETTLED".equals(row.state())) {
            return new BudgetSnapshot(row.reserved(), row.consumed(), row.released(), row.state());
        }
        if (!"RESERVED".equals(row.state())) {
            throw new BusinessException("RESEARCH_BUDGET_NOT_RELEASABLE", "Budget reservation is not releasable");
        }
        Map<String, Long> released = subtract(row.reserved(), row.consumed());
        int updated = jdbcTemplate.update("""
                update research_budget_reservation
                set released_json = ?, state = 'RELEASED', settled_at = current_timestamp, updated_at = current_timestamp
                where id = ? and state = 'RESERVED'
                """, Json.write(objectMapper, released), row.id());
        if (updated != 1) throw new BusinessException("RESEARCH_BUDGET_NOT_RELEASABLE", "Budget reservation lost release race");
        return new BudgetSnapshot(row.reserved(), row.consumed(), released, "RELEASED");
    }

    /**
     * Internal lifecycle authority. The caller has already transitioned the
     * task, but this method independently re-locks and verifies the complete
     * run -> task -> reservation identity before releasing any budget.
     */
    @Transactional
    BudgetSnapshot releaseForLifecycle(String runId, String taskId, String reservationId) {
        String runStatus = jdbcTemplate.query("""
                select status from research_run where id = ? for update
                """, rs -> rs.next() ? rs.getString(1) : null, runId);
        if (runStatus == null) {
            throw new BusinessException("RESEARCH_AGENT_RUN_NOT_FOUND", "Lifecycle budget run does not exist");
        }
        String taskStatus = jdbcTemplate.query("""
                select status from research_agent_task where id = ? and research_run_id = ? for update
                """, rs -> rs.next() ? rs.getString(1) : null, taskId, runId);
        if (!"CANCELLED".equals(taskStatus) && !"FAILED".equals(taskStatus)) {
            throw new BusinessException("RESEARCH_BUDGET_NOT_RELEASABLE",
                    "Lifecycle budget release requires a cancelled or failed task");
        }
        Integer completions = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_completion where research_agent_task_id = ?
                """, Integer.class, taskId);
        if (completions == null || completions != 0) {
            throw new BusinessException("RESEARCH_BUDGET_NOT_RELEASABLE",
                    "Completed atomic budget cannot be released by lifecycle");
        }
        ReservationRow row = requireReservation(reservationId);
        if (!runId.equals(row.runId()) || !taskId.equals(row.taskId()) || !"RESERVED".equals(row.state())) {
            throw new BusinessException("RESEARCH_BUDGET_NOT_RELEASABLE",
                    "Lifecycle budget reservation identity or state is invalid");
        }
        Map<String, Long> released = subtract(row.reserved(), row.consumed());
        int updated = jdbcTemplate.update("""
                update research_budget_reservation
                set released_json = ?, state = 'RELEASED', settled_at = current_timestamp,
                    updated_at = current_timestamp
                where id = ? and research_run_id = ? and research_agent_task_id = ?
                  and state = 'RESERVED' and agent_completion_id is null
                """, Json.write(objectMapper, released), row.id(), runId, taskId);
        if (updated != 1) {
            throw new BusinessException("RESEARCH_BUDGET_NOT_RELEASABLE",
                    "Lifecycle budget reservation lost its release race");
        }
        return new BudgetSnapshot(row.reserved(), row.consumed(), released, "RELEASED");
    }

    @Transactional
    public CheckpointReceipt appendCheckpoint(CheckpointCommand command) {
        validateCheckpoint(command);
        lockRunnableRun(command.researchRunId());
        CheckpointRow latest = latestCheckpoint(command.researchRunId());
        if (latest != null && (command.taskHighWaterMark() < latest.taskHighWaterMark()
                || command.candidateHighWaterMark() < latest.candidateHighWaterMark()
                || command.mergeHighWaterMark() < latest.mergeHighWaterMark())) {
            throw new BusinessException("RESEARCH_AGENT_CHECKPOINT_HIGH_WATER_REGRESSION", "Checkpoint high-water mark cannot regress");
        }
        int sequence = latest == null ? 1 : latest.sequence() + 1;
        String id = Ids.newId();
        jdbcTemplate.update("""
                insert into research_agent_checkpoint(
                    id, research_run_id, checkpoint_seq, wave_no, round_no, plan_revision, entity_set_version,
                    ledger_hash, task_high_water_mark, candidate_high_water_mark, merge_high_water_mark,
                    budget_summary_json, summary_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, command.researchRunId(), sequence, command.waveNo(), command.roundNo(), command.planRevision(),
                command.entitySetVersion(), command.ledgerHash(), command.taskHighWaterMark(), command.candidateHighWaterMark(),
                command.mergeHighWaterMark(), Json.write(objectMapper, command.budgetSummary()), Json.write(objectMapper, command.summary()));
        return new CheckpointReceipt(id, sequence);
    }

    private void requireReservableTask(String runId, String taskId) {
        String state = jdbcTemplate.query("""
                select status from research_agent_task where id = ? and research_run_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, taskId, runId);
        if (state == null) throw new BusinessException("RESEARCH_BUDGET_TASK_NOT_FOUND", "Research agent task does not exist for run");
        if ("SUBMITTED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state)) {
            throw new BusinessException("RESEARCH_BUDGET_TASK_TERMINAL", "Cannot reserve budget for terminal task");
        }
    }

    private void lockRunnableRun(String runId) {
        String status = jdbcTemplate.query("select status from research_run where id = ? for update", rs -> rs.next() ? rs.getString(1) : null, runId);
        if (status == null) throw new BusinessException("RESEARCH_AGENT_RUN_NOT_FOUND", "Research run does not exist");
        if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
            throw new BusinessException("RESEARCH_AGENT_RUN_TERMINAL", "Cannot checkpoint terminal run");
        }
    }

    private ReservationRow requireReservation(String id) {
        ReservationRow row = jdbcTemplate.query("""
                select id, research_run_id, research_agent_task_id, idempotency_key, reserved_json, consumed_json, released_json, state
                from research_budget_reservation where id = ? for update
                """, rs -> rs.next() ? mapReservation(rs) : null, id);
        if (row == null) throw new BusinessException("RESEARCH_BUDGET_RESERVATION_NOT_FOUND", "Budget reservation does not exist");
        return row;
    }

    /**
     * Uses the same run -> task -> reservation lock order as atomic completion.
     * The initial identity read is non-locking; immutable reservation FKs are
     * rechecked after all authoritative rows have been locked.
     */
    private ReservationRow lockDirectMutationScope(String reservationId) {
        ReservationIdentity discovered = jdbcTemplate.query("""
                select research_run_id, research_agent_task_id
                from research_budget_reservation where id = ?
                """, rs -> rs.next() ? new ReservationIdentity(rs.getString(1), rs.getString(2)) : null,
                reservationId);
        if (discovered == null) {
            throw new BusinessException("RESEARCH_BUDGET_RESERVATION_NOT_FOUND", "Budget reservation does not exist");
        }
        String executionMode = jdbcTemplate.query("""
                select agent_execution_mode from research_run where id = ? for update
                """, rs -> rs.next() ? rs.getString(1) : null, discovered.runId());
        if (executionMode == null) {
            throw new BusinessException("RESEARCH_BUDGET_TASK_NOT_FOUND", "Reservation run does not exist");
        }
        TaskMutationPolicy task = jdbcTemplate.query("""
                select role, snapshot_schema_version
                from research_agent_task where id = ? and research_run_id = ? for update
                """, rs -> rs.next() ? new TaskMutationPolicy(rs.getString(1), rs.getString(2)) : null,
                discovered.taskId(), discovered.runId());
        if (task == null) {
            throw new BusinessException("RESEARCH_BUDGET_TASK_NOT_FOUND", "Reservation task does not exist for run");
        }
        ReservationRow locked = requireReservation(reservationId);
        if (!discovered.runId().equals(locked.runId()) || !discovered.taskId().equals(locked.taskId())) {
            throw new IllegalStateException("Budget reservation identity changed while acquiring locks");
        }
        boolean atomicDeepCell = "DEEP_CELL".equals(task.role())
                && (ATOMIC_SNAPSHOT_SCHEMA_V1.equals(task.snapshotSchemaVersion())
                || ATOMIC_SNAPSHOT_SCHEMA_V2.equals(task.snapshotSchemaVersion()));
        boolean atomicCounterfactual = "COUNTERFACTUAL".equals(task.role())
                && ATOMIC_SNAPSHOT_SCHEMA_V2.equals(task.snapshotSchemaVersion());
        if ("INCREMENTAL_V1".equals(executionMode) && (atomicDeepCell || atomicCounterfactual)) {
            throw new BusinessException("RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED",
                    "Snapshot-ready research task budget must be finalized by atomic completion");
        }
        return locked;
    }

    private ReservationRow findByIdempotency(String runId, String key) {
        return jdbcTemplate.query("""
                select id, research_run_id, research_agent_task_id, idempotency_key, reserved_json, consumed_json, released_json, state
                from research_budget_reservation where research_run_id = ? and idempotency_key = ?
                """, rs -> rs.next() ? mapReservation(rs) : null, runId, key);
    }

    private ReservationRow mapReservation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReservationRow(rs.getString("id"), rs.getString("research_run_id"), rs.getString("research_agent_task_id"),
                rs.getString("idempotency_key"), readMap(rs.getString("reserved_json")), readMap(rs.getString("consumed_json")),
                readMap(rs.getString("released_json")), rs.getString("state"));
    }

    private CheckpointRow latestCheckpoint(String runId) {
        return jdbcTemplate.query("""
                select checkpoint_seq, task_high_water_mark, candidate_high_water_mark, merge_high_water_mark
                from research_agent_checkpoint where research_run_id = ? order by checkpoint_seq desc limit 1
                """, rs -> rs.next() ? new CheckpointRow(rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) : null, runId);
    }

    private Map<String, Long> readMap(String json) {
        try {
            Map<String, Long> values = objectMapper.readValue(json, new TypeReference<Map<String, Long>>() { });
            return normalize(values);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("RESEARCH_BUDGET_JSON_INVALID", "Budget ledger payload is invalid");
        }
    }

    private Map<String, Long> normalize(Map<String, Long> source) {
        LinkedHashMap<String, Long> normalized = new LinkedHashMap<>();
        if (source != null) source.forEach((key, value) -> normalized.put(key, value == null ? 0L : value));
        return Map.copyOf(normalized);
    }

    private Map<String, Long> zeroLike(Map<String, Long> source) {
        LinkedHashMap<String, Long> zero = new LinkedHashMap<>();
        normalize(source).keySet().forEach(key -> zero.put(key, 0L));
        return Map.copyOf(zero);
    }

    private Map<String, Long> subtract(Map<String, Long> reserved, Map<String, Long> consumed) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        reserved.forEach((key, value) -> result.put(key, value - consumed.getOrDefault(key, 0L)));
        return Map.copyOf(result);
    }

    private boolean within(Map<String, Long> used, Map<String, Long> reserved) {
        return used.entrySet().stream().allMatch(item -> item.getValue() <= reserved.getOrDefault(item.getKey(), 0L));
    }

    private void requireNonNegative(Map<String, Long> values) {
        if (values == null || values.isEmpty() || normalize(values).values().stream().anyMatch(value -> value < 0)) {
            throw new BusinessException("RESEARCH_BUDGET_INVALID", "Budget values must be non-negative and non-empty");
        }
    }

    private void validateCheckpoint(CheckpointCommand command) {
        if (blank(command.researchRunId()) || blank(command.ledgerHash()) || command.waveNo() < 1 || command.roundNo() < 1
                || command.planRevision() < 0 || command.entitySetVersion() < 0 || command.taskHighWaterMark() < 0
                || command.candidateHighWaterMark() < 0 || command.mergeHighWaterMark() < 0
                || command.budgetSummary() == null || command.summary() == null) {
            throw new BusinessException("RESEARCH_AGENT_CHECKPOINT_INVALID", "Checkpoint contract is invalid");
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record ReserveCommand(String researchRunId, String taskId, String idempotencyKey, Map<String, Long> reserved) { }
    public record SettleCommand(String reservationId, Map<String, Long> consumed) { }
    public record ReservationReceipt(String reservationId, boolean idempotentReplay) { }
    public record BudgetSnapshot(Map<String, Long> reserved, Map<String, Long> consumed, Map<String, Long> released, String state) { }
    public record CheckpointCommand(String researchRunId, int waveNo, int roundNo, int planRevision, int entitySetVersion,
                                    String ledgerHash, long taskHighWaterMark, long candidateHighWaterMark, long mergeHighWaterMark,
                                    Map<String, Object> budgetSummary, Map<String, Object> summary) { }
    public record CheckpointReceipt(String checkpointId, int checkpointSeq) { }
    private record ReservationRow(String id, String runId, String taskId, String idempotencyKey,
                                  Map<String, Long> reserved, Map<String, Long> consumed, Map<String, Long> released, String state) { }
    private record ReservationIdentity(String runId, String taskId) { }
    private record TaskMutationPolicy(String role, String snapshotSchemaVersion) { }
    private record CheckpointRow(int sequence, long taskHighWaterMark, long candidateHighWaterMark, long mergeHighWaterMark) { }
}
