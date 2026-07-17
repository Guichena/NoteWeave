package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MA3B durable lifecycle for research-agent task leases. */
@Service
public class ResearchAgentTaskService {

    private static final int MAX_LEASE_SECONDS = 300;
    private static final String TASK_SNAPSHOT_SCHEMA = "research-agent-task-snapshot.v1";
    private static final String TASK_SNAPSHOT_SCHEMA_V2 = "research-agent-task-snapshot.v2";
    private static final Set<String> SNAPSHOT_ROLES = Set.of(
            "DEEP_CELL", "WIDE_DISCOVERY", "COUNTERFACTUAL", "EVIDENCE_AUDIT", "SYNTHESIS"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchBudgetAndCheckpointService budgetService;
    private final ResearchAgentTaskSnapshotCanonicalizer snapshotCanonicalizer;

    public ResearchAgentTaskService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchBudgetAndCheckpointService budgetService,
            ResearchAgentTaskSnapshotCanonicalizer snapshotCanonicalizer
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.budgetService = budgetService;
        this.snapshotCanonicalizer = snapshotCanonicalizer;
    }

    @Transactional
    public TaskSnapshot createTask(CreateTaskCommand command) {
        validateCreate(command);
        TaskRow existing = findByIdempotency(command.researchRunId(), command.idempotencyKey());
        if (existing != null) {
            if (!existing.taskKey().equals(command.taskKey())) {
                throw new BusinessException("RESEARCH_AGENT_TASK_IDEMPOTENCY_CONFLICT", "Task idempotency key is bound to a different task");
            }
            return snapshot(existing);
        }
        requireRunnableRun(command.researchRunId());
        String taskId = Ids.newId();
        try {
            jdbcTemplate.update("""
                    insert into research_agent_task(
                        id, research_run_id, task_key, idempotency_key, wave_no, role, entity_id, branch_id,
                        plan_revision, entity_set_version, target_cells_json, budget_json,
                        target_bindings_json, execution_context_json, snapshot_schema_version, status
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                    """,
                    taskId, command.researchRunId(), command.taskKey(), command.idempotencyKey(), command.waveNo(),
                    command.role(), command.entityId(), command.branchId(), command.planRevision(), command.entitySetVersion(),
                    Json.write(objectMapper, command.targetCells()), Json.write(objectMapper, command.budget()),
                    snapshotReady(command) ? Json.write(objectMapper, targetBindingPayload(command.targetBindings())) : null,
                    snapshotReady(command) ? Json.write(objectMapper, executionContextPayload(command.executionContext())) : null,
                    snapshotReady(command) ? TASK_SNAPSHOT_SCHEMA : null
            );
            return snapshot(requireTask(taskId));
        } catch (DataIntegrityViolationException duplicate) {
            TaskRow replay = findByIdempotency(command.researchRunId(), command.idempotencyKey());
            if (replay != null && replay.taskKey().equals(command.taskKey())) {
                return snapshot(replay);
            }
            throw duplicate;
        }
    }

    @Transactional
    public ClaimedTask claimTask(ClaimCommand command) {
        TaskRow discovered = requireTask(command.taskId());
        RunGuard run = lockRunAndTask(discovered);
        TaskRow locked = requireTask(command.taskId());
        if (!run.runnableIncremental()) {
            throw new BusinessException("RESEARCH_AGENT_TASK_NOT_CLAIMABLE", "Research agent task is not claimable");
        }
        int leaseSeconds = leaseSeconds(command.leaseSeconds());
        int claimed = jdbcTemplate.update("""
                update research_agent_task
                set status = 'CLAIMED', lease_epoch = lease_epoch + 1, fencing_token = fencing_token + 1,
                    worker_instance_id = ?,
                    lease_expires_at = timestampadd(second, ?, current_timestamp),
                    attempt_count = attempt_count + 1,
                    snapshot_digest = null,
                    updated_at = current_timestamp
                where id = ? and (status in ('PENDING', 'EXPIRED')
                    or (status = 'RETRY_WAIT' and (next_attempt_at is null or next_attempt_at <= current_timestamp)))
                """, command.workerInstanceId(), leaseSeconds, command.taskId());
        if (claimed != 1) {
            if (isReplayableClaim(locked, command.workerInstanceId())) {
                return claimedTask(locked);
            }
            throw new BusinessException("RESEARCH_AGENT_TASK_NOT_CLAIMABLE", "Research agent task is not claimable");
        }
        TaskRow task = requireTask(command.taskId());
        bindClaimedTargetCells(task);
        return claimedTask(task);
    }

    @Transactional
    public ClaimedTask heartbeat(LeaseCommand command) {
        TaskRow discovered = requireTask(command.taskId());
        RunGuard run = lockRunAndTask(discovered);
        if (!run.runnableIncremental()) {
            throw new BusinessException("RESEARCH_AGENT_TASK_STALE_LEASE", "Research agent lease is stale or owned by another worker");
        }
        int leaseSeconds = leaseSeconds(command.leaseSeconds());
        int updated = jdbcTemplate.update("""
                update research_agent_task
                set status = 'RUNNING',
                    lease_expires_at = timestampadd(second, ?, current_timestamp),
                    updated_at = current_timestamp
                where id = ? and status in ('CLAIMED', 'RUNNING')
                  and worker_instance_id = ? and lease_epoch = ? and fencing_token = ?
                  and lease_expires_at > current_timestamp
                """, leaseSeconds, command.taskId(), command.workerInstanceId(), command.leaseEpoch(), command.fencingToken());
        if (updated != 1) {
            throw new BusinessException("RESEARCH_AGENT_TASK_STALE_LEASE", "Research agent lease is stale or owned by another worker");
        }
        TaskRow task = requireTask(command.taskId());
        return claimedTask(task);
    }

    @Transactional
    public int expireLeases() {
        List<String> runIds = jdbcTemplate.query("""
                select distinct research_run_id from research_agent_task
                where status in ('CLAIMED', 'RUNNING')
                  and lease_expires_at is not null and lease_expires_at <= current_timestamp
                order by research_run_id
                """, (rs, rowNum) -> rs.getString(1));
        int expired = 0;
        for (String runId : runIds) {
            jdbcTemplate.query("select id from research_run where id = ? for update",
                    rs -> rs.next() ? rs.getString(1) : null, runId);
            List<String> expiredTaskIds = jdbcTemplate.query("""
                    select id from research_agent_task
                    where research_run_id = ? and status in ('CLAIMED', 'RUNNING')
                      and lease_expires_at is not null and lease_expires_at <= current_timestamp
                    order by id for update
                    """, (rs, rowNum) -> rs.getString(1), runId);
            for (String taskId : expiredTaskIds) {
                int updated = jdbcTemplate.update("""
                        update research_agent_task
                        set status = 'EXPIRED', worker_instance_id = null, lease_expires_at = null,
                            updated_at = current_timestamp
                        where id = ? and status in ('CLAIMED', 'RUNNING')
                          and lease_expires_at is not null and lease_expires_at <= current_timestamp
                        """, taskId);
                if (updated == 1) {
                    expired++;
                    releaseCellBindings(taskId);
                }
            }
        }
        return expired;
    }

    @Deprecated(forRemoval = true)
    @Transactional
    public ExecutionReceipt submitExecution(SubmitCommand command) {
        rejectSplitCompletionIfRequired(command.taskId());
        ExecutionReceipt replay = findExecution(command.taskId(), command.executionKey());
        if (replay != null) return replay;
        TaskRow discovered = requireTask(command.taskId());
        lockRunAndTask(discovered);
        int updated = jdbcTemplate.update("""
                update research_agent_task
                set status = 'SUBMITTED', terminal_at = current_timestamp, updated_at = current_timestamp
                where id = ? and status in ('CLAIMED', 'RUNNING')
                  and worker_instance_id = ? and lease_epoch = ? and fencing_token = ?
                  and lease_expires_at > current_timestamp
                  and exists (
                    select 1 from research_run rr
                    where rr.id = research_agent_task.research_run_id
                      and rr.agent_execution_mode = 'INCREMENTAL_V1'
                      and rr.status not in ('COMPLETED', 'FAILED', 'CANCELLED')
                  )
                """, command.taskId(), command.workerInstanceId(), command.leaseEpoch(), command.fencingToken());
        if (updated != 1) {
            ExecutionReceipt concurrentReplay = findExecution(command.taskId(), command.executionKey());
            if (concurrentReplay != null) return concurrentReplay;
            throw new BusinessException("RESEARCH_AGENT_TASK_STALE_LEASE", "Task execution cannot be submitted with this lease");
        }
        TaskRow task = requireTask(command.taskId());
        String executionId = Ids.newId();
        try {
            jdbcTemplate.update("""
                    insert into research_agent_execution(
                        id, research_agent_task_id, execution_key, lease_epoch, fencing_token, worker_instance_id,
                        status, termination_reason, usage_json, trace_digest
                    ) values (?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, ?, ?)
                    """, executionId, command.taskId(), command.executionKey(), command.leaseEpoch(), command.fencingToken(),
                    command.workerInstanceId(), command.terminationReason(), Json.write(objectMapper, command.usage()), command.traceDigest());
            BudgetSnapshotAdapter.settleIfReserved(budgetService, command.taskId(), command.executionKey(), task.researchRunId(), command.usage());
            jdbcTemplate.update("""
                    update research_agent_outbox
                    set status = 'CANCELLED', updated_at = current_timestamp
                    where research_agent_task_id = ? and status = 'READY'
                    """, command.taskId());
            releaseCellBindings(command.taskId());
            return new ExecutionReceipt(executionId, false);
        } catch (DataIntegrityViolationException duplicate) {
            ExecutionReceipt concurrentReplay = findExecution(command.taskId(), command.executionKey());
            if (concurrentReplay != null) return concurrentReplay;
            throw duplicate;
        }
    }

    private void requireRunnableRun(String runId) {
        String status = jdbcTemplate.query("select status from research_run where id = ?", rs -> rs.next() ? rs.getString(1) : null, runId);
        if (status == null) throw new BusinessException("RESEARCH_AGENT_RUN_NOT_FOUND", "Research run does not exist");
        if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
            throw new BusinessException("RESEARCH_AGENT_RUN_TERMINAL", "Cannot create agent task for terminal run");
        }
    }

    private TaskRow requireTask(String taskId) {
        TaskRow task = jdbcTemplate.query("""
                select rat.id, rat.research_run_id, rat.task_key, rat.idempotency_key, rat.status, rat.lease_epoch, rat.fencing_token,
                       rat.worker_instance_id, rat.lease_expires_at, rat.target_cells_json, rat.budget_json,
                       rat.role, rat.entity_id, rat.branch_id, rat.plan_revision, rat.entity_set_version,
                       rat.target_bindings_json, rat.execution_context_json, rat.snapshot_schema_version, rat.snapshot_digest,
                       rat.logical_task_key, rat.quorum_group_key, rat.candidate_quorum, rat.candidate_slot,
                       rr.workspace_id,
                       case when rat.lease_expires_at > current_timestamp then true else false end as lease_valid
                from research_agent_task rat join research_run rr on rr.id = rat.research_run_id where rat.id = ?
                """, rs -> rs.next() ? new TaskRow(
                rs.getString("id"), rs.getString("research_run_id"), rs.getString("task_key"), rs.getString("idempotency_key"),
                rs.getString("status"), rs.getInt("lease_epoch"), rs.getLong("fencing_token"),
                rs.getString("worker_instance_id"),
                rs.getTimestamp("lease_expires_at") == null ? null : rs.getTimestamp("lease_expires_at").toInstant(),
                rs.getString("target_cells_json"), rs.getString("budget_json"), rs.getString("role"), rs.getString("entity_id"),
                rs.getString("branch_id"), rs.getInt("plan_revision"), rs.getInt("entity_set_version"),
                rs.getString("target_bindings_json"), rs.getString("execution_context_json"),
                 rs.getString("snapshot_schema_version"), rs.getString("snapshot_digest"),
                 rs.getString("logical_task_key"), rs.getString("quorum_group_key"),
                 rs.getInt("candidate_quorum"), rs.getInt("candidate_slot"), rs.getString("workspace_id"),
                rs.getBoolean("lease_valid")) : null, taskId);
        if (task == null) throw new BusinessException("RESEARCH_AGENT_TASK_NOT_FOUND", "Research agent task does not exist");
        return task;
    }

    private TaskRow findByIdempotency(String runId, String idempotencyKey) {
        return jdbcTemplate.query("""
                select rat.id, rat.research_run_id, rat.task_key, rat.idempotency_key, rat.status, rat.lease_epoch, rat.fencing_token,
                       rat.worker_instance_id, rat.lease_expires_at, rat.target_cells_json, rat.budget_json,
                       rat.role, rat.entity_id, rat.branch_id, rat.plan_revision, rat.entity_set_version,
                       rat.target_bindings_json, rat.execution_context_json, rat.snapshot_schema_version, rat.snapshot_digest,
                       rat.logical_task_key, rat.quorum_group_key, rat.candidate_quorum, rat.candidate_slot,
                       rr.workspace_id,
                       case when rat.lease_expires_at > current_timestamp then true else false end as lease_valid
                from research_agent_task rat join research_run rr on rr.id = rat.research_run_id
                where rat.research_run_id = ? and rat.idempotency_key = ?
                """, rs -> rs.next() ? new TaskRow(
                rs.getString("id"), rs.getString("research_run_id"), rs.getString("task_key"), rs.getString("idempotency_key"),
                rs.getString("status"), rs.getInt("lease_epoch"), rs.getLong("fencing_token"),
                rs.getString("worker_instance_id"),
                rs.getTimestamp("lease_expires_at") == null ? null : rs.getTimestamp("lease_expires_at").toInstant(),
                rs.getString("target_cells_json"), rs.getString("budget_json"), rs.getString("role"), rs.getString("entity_id"),
                rs.getString("branch_id"), rs.getInt("plan_revision"), rs.getInt("entity_set_version"),
                rs.getString("target_bindings_json"), rs.getString("execution_context_json"),
                 rs.getString("snapshot_schema_version"), rs.getString("snapshot_digest"),
                 rs.getString("logical_task_key"), rs.getString("quorum_group_key"),
                 rs.getInt("candidate_quorum"), rs.getInt("candidate_slot"), rs.getString("workspace_id"),
                rs.getBoolean("lease_valid")) : null, runId, idempotencyKey);
    }

    private ExecutionReceipt findExecution(String taskId, String executionKey) {
        return jdbcTemplate.query("""
                select id from research_agent_execution where research_agent_task_id = ? and execution_key = ?
                """, rs -> rs.next() ? new ExecutionReceipt(rs.getString("id"), true) : null, taskId, executionKey);
    }

    private TaskSnapshot snapshot(TaskRow task) {
        return new TaskSnapshot(task.id(), task.status());
    }

    private ClaimedTask claimedTask(TaskRow task) {
        if (!isSnapshotSchema(task.snapshotSchemaVersion())) {
            return new ClaimedTask(task.id(), task.leaseEpoch(), task.fencingToken(), task.leaseExpiresAt(),
                    task.targetCellsJson(), task.budgetJson(), null, null);
        }
        CanonicalSnapshot snapshot = canonicalSnapshot(task);
        if (!snapshot.digest().equals(task.snapshotDigest())) {
            jdbcTemplate.update("update research_agent_task set snapshot_digest = ?, updated_at = current_timestamp where id = ?",
                    snapshot.digest(), task.id());
        }
        return new ClaimedTask(task.id(), task.leaseEpoch(), task.fencingToken(), task.leaseExpiresAt(),
                task.targetCellsJson(), task.budgetJson(), snapshot.json(), snapshot.digest());
    }

    /**
     * Binds every versioned snapshot target to the same claimed lease before
     * the Worker receives a snapshot.  This is deliberately all-or-nothing:
     * a partial task must never be allowed to propose a merge.
     */
    private void bindClaimedTargetCells(TaskRow task) {
        if (!isSnapshotSchema(task.snapshotSchemaVersion())) return;
        List<Map<String, Object>> bindings = new ArrayList<>(
                readList(task.targetBindingsJson(), "RESEARCH_AGENT_TASK_SNAPSHOT_INVALID"));
        bindings.sort((left, right) -> ResearchAgentBinaryOrder.UTF8.compare(
                String.valueOf(left.get("cell_id")), String.valueOf(right.get("cell_id"))));
        String bindingOwner = task.candidateQuorum() == 2 ? task.quorumGroupKey() : task.id();
        for (Map<String, Object> binding : bindings) {
            Object cellId = binding.get("cell_id");
            Object expectedVersion = binding.get("expected_version");
            if (!(cellId instanceof String cellKey) || cellKey.isBlank() || !(expectedVersion instanceof Number version)) {
                throw new BusinessException("RESEARCH_AGENT_TASK_SNAPSHOT_INVALID", "Task target binding is invalid");
            }
            int updated = task.candidateQuorum() == 2
                    ? jdbcTemplate.update("""
                            update research_cell
                            set active_task_id = ?, updated_at = current_timestamp
                            where research_run_id = ? and cell_key = ? and cell_version = ?
                              and plan_revision = ? and entity_set_version = ?
                              and (active_task_id is null or active_task_id = ?)
                            """, bindingOwner, task.researchRunId(), cellKey, version.intValue(),
                            task.planRevision(), task.entitySetVersion(), bindingOwner)
                    : jdbcTemplate.update("""
                            update research_cell
                            set active_task_id = ?, lease_epoch = ?, fencing_token = ?, updated_at = current_timestamp
                            where research_run_id = ? and cell_key = ? and cell_version = ?
                              and plan_revision = ? and entity_set_version = ?
                              and (active_task_id is null or active_task_id = ?)
                            """, bindingOwner, task.leaseEpoch(), task.fencingToken(), task.researchRunId(), cellKey,
                            version.intValue(), task.planRevision(), task.entitySetVersion(), bindingOwner);
            if (updated != 1) {
                throw new BusinessException("RESEARCH_AGENT_TASK_TARGET_STALE", "A task target no longer matches its snapshot");
            }
        }
    }

    private void releaseCellBindings(String taskId) {
        jdbcTemplate.query("""
                select id from research_cell where active_task_id = ?
                order by cast(cell_key as binary), id for update
                """, (rs, rowNum) -> rs.getString(1), taskId);
        jdbcTemplate.update("""
                update research_cell
                set active_task_id = null, updated_at = current_timestamp
                where active_task_id = ?
                """, taskId);
    }

    private CanonicalSnapshot canonicalSnapshot(TaskRow task) {
        ResearchAgentTaskSnapshotCanonicalizer.CanonicalSnapshot snapshot = snapshotCanonicalizer.canonicalize(
                new ResearchAgentTaskSnapshotCanonicalizer.SnapshotInput(
                        task.snapshotSchemaVersion(), task.id(), task.researchRunId(), task.workspaceId(), task.role(), task.entityId(),
                        task.branchId(), task.planRevision(), task.entitySetVersion(), task.leaseEpoch(),
                        task.fencingToken(), task.targetBindingsJson(), task.budgetJson(), task.executionContextJson(),
                        task.logicalTaskKey(), task.quorumGroupKey(), task.candidateQuorum(), task.candidateSlot(),
                        task.candidateQuorum() == 2));
        return new CanonicalSnapshot(snapshot.json(), snapshot.digest());
    }

    private Map<String, Object> executionContextPayload(TaskExecutionContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider_key", context.providerKey());
        payload.put("source_policy", context.sourcePolicy());
        payload.put("query_policy", context.queryPolicy());
        return payload;
    }

    private List<Map<String, Object>> targetBindingPayload(List<TargetCellBinding> bindings) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (TargetCellBinding binding : bindings) {
            payload.add(Map.of("cell_id", binding.cellId(), "expected_version", binding.expectedVersion()));
        }
        return payload;
    }

    private boolean snapshotReady(CreateTaskCommand command) {
        return command.targetBindings() != null && command.executionContext() != null;
    }

    private boolean isSnapshotSchema(String value) {
        return TASK_SNAPSHOT_SCHEMA.equals(value) || TASK_SNAPSHOT_SCHEMA_V2.equals(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String rawJson, String errorCode) {
        try {
            Map<String, Object> map = objectMapper.readValue(rawJson, new TypeReference<Map<String, Object>>() { });
            if (map == null) throw new BusinessException(errorCode, "Task snapshot JSON is invalid");
            return map;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new BusinessException(errorCode, "Task snapshot JSON is invalid");
        }
    }

    private List<Map<String, Object>> readList(String rawJson, String errorCode) {
        try {
            List<Map<String, Object>> list = objectMapper.readValue(rawJson, new TypeReference<List<Map<String, Object>>>() { });
            if (list == null || list.isEmpty()) throw new BusinessException(errorCode, "Task snapshot JSON is invalid");
            return list;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new BusinessException(errorCode, "Task snapshot JSON is invalid");
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> sorted = new TreeMap<>();
            rawMap.forEach((key, child) -> sorted.put(String.valueOf(key), canonicalize(child)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalize).toList();
        }
        return value;
    }

    private String sha256(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private int leaseSeconds(int requested) {
        return Math.max(1, Math.min(MAX_LEASE_SECONDS, requested));
    }

    private void validateCreate(CreateTaskCommand command) {
        if (blank(command.researchRunId()) || blank(command.taskKey()) || blank(command.idempotencyKey())
                || blank(command.role()) || blank(command.entityId()) || blank(command.branchId())
                || command.waveNo() < 1 || command.planRevision() < 0 || command.entitySetVersion() < 0
                || command.targetCells() == null || command.targetCells().isEmpty() || command.budget() == null) {
            throw new BusinessException("RESEARCH_AGENT_TASK_INVALID", "Research agent task contract is invalid");
        }
        boolean bindingsPresent = command.targetBindings() != null;
        boolean contextPresent = command.executionContext() != null;
        if (bindingsPresent != contextPresent) {
            throw new BusinessException("RESEARCH_AGENT_TASK_SNAPSHOT_INVALID", "Task snapshot scope must be supplied as a complete unit");
        }
        if (!bindingsPresent) return; // Legacy MA3/MA4A transport task: intentionally not eligible for MA4D execution.
        if (!SNAPSHOT_ROLES.contains(command.role()) || command.targetBindings().isEmpty()
                || command.targetBindings().size() != command.targetCells().size()) {
            throw new BusinessException("RESEARCH_AGENT_TASK_SNAPSHOT_INVALID", "Task snapshot bindings are invalid");
        }
        Set<String> legacyCells = new HashSet<>(command.targetCells());
        Set<String> bindingCells = new HashSet<>();
        for (TargetCellBinding binding : command.targetBindings()) {
            if (binding == null || blank(binding.cellId()) || binding.expectedVersion() < 0 || !bindingCells.add(binding.cellId())) {
                throw new BusinessException("RESEARCH_AGENT_TASK_SNAPSHOT_INVALID", "Task snapshot bindings are invalid");
            }
        }
        TaskExecutionContext context = command.executionContext();
        if (!legacyCells.equals(bindingCells) || blank(context.providerKey())
                || context.sourcePolicy() == null || context.sourcePolicy().isEmpty()
                || context.queryPolicy() == null || context.queryPolicy().isEmpty()) {
            throw new BusinessException("RESEARCH_AGENT_TASK_SNAPSHOT_INVALID", "Task snapshot context is invalid");
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private boolean isReplayableClaim(TaskRow task, String workerInstanceId) {
        return workerInstanceId.equals(task.workerInstanceId())
                && ("CLAIMED".equals(task.status()) || "RUNNING".equals(task.status()))
                && task.leaseExpiresAt() != null
                && task.leaseValid();
    }

    private boolean isRunnableIncrementalRun(String runId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from research_run
                where id = ? and agent_execution_mode = 'INCREMENTAL_V1'
                  and status not in ('COMPLETED', 'FAILED', 'CANCELLED')
                """, Integer.class, runId);
        return count != null && count == 1;
    }

    private RunGuard lockRunAndTask(TaskRow discovered) {
        RunGuard run = jdbcTemplate.query("""
                select status, agent_execution_mode from research_run where id = ? for update
                """, rs -> rs.next() ? new RunGuard(rs.getString(1), rs.getString(2)) : null,
                discovered.researchRunId());
        if (run == null) throw new BusinessException("RESEARCH_AGENT_RUN_NOT_FOUND", "Research run does not exist");
        String lockedTask = jdbcTemplate.query("""
                select id from research_agent_task where id = ? and research_run_id = ? for update
                """, rs -> rs.next() ? rs.getString(1) : null, discovered.id(), discovered.researchRunId());
        if (lockedTask == null) throw new BusinessException("RESEARCH_AGENT_TASK_NOT_FOUND", "Research agent task does not exist");
        return run;
    }

    private void rejectSplitCompletionIfRequired(String taskId) {
        Integer required = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task rat
                join research_run rr on rr.id = rat.research_run_id
                where rat.id = ?
                  and ((rat.role = 'DEEP_CELL' and rat.snapshot_schema_version in (
                          'research-agent-task-snapshot.v1', 'research-agent-task-snapshot.v2'))
                       or (rat.role = 'COUNTERFACTUAL'
                           and rat.snapshot_schema_version = 'research-agent-task-snapshot.v2'))
                  and rr.agent_execution_mode = 'INCREMENTAL_V1'
                """, Integer.class, taskId);
        if (required != null && required == 1) {
            throw new BusinessException("RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED",
                    "Snapshot-ready research agent tasks must use the atomic completion endpoint");
        }
    }

    public record CreateTaskCommand(
            String researchRunId, String taskKey, String idempotencyKey, int waveNo, String role,
            String entityId, String branchId, int planRevision, int entitySetVersion,
            List<String> targetCells, Map<String, Object> budget,
            List<TargetCellBinding> targetBindings, TaskExecutionContext executionContext
    ) {
        public CreateTaskCommand(String researchRunId, String taskKey, String idempotencyKey, int waveNo, String role,
                                 String entityId, String branchId, int planRevision, int entitySetVersion,
                                 List<String> targetCells, Map<String, Object> budget) {
            this(researchRunId, taskKey, idempotencyKey, waveNo, role, entityId, branchId, planRevision,
                    entitySetVersion, targetCells, budget, null, null);
        }
    }
    public record TargetCellBinding(String cellId, int expectedVersion) { }
    public record TaskExecutionContext(String providerKey, Map<String, Object> sourcePolicy, Map<String, Object> queryPolicy) { }
    public record TaskSnapshot(String taskId, String status) { }
    public record ClaimCommand(String taskId, String workerInstanceId, int leaseSeconds) { }
    public record LeaseCommand(String taskId, String workerInstanceId, int leaseEpoch, long fencingToken, int leaseSeconds) { }
    public record ClaimedTask(String taskId, int leaseEpoch, long fencingToken, Instant leaseExpiresAt,
                              String targetCellsJson, String budgetJson, String taskSnapshotJson, String snapshotDigest) { }
    public record SubmitCommand(String taskId, String workerInstanceId, int leaseEpoch, long fencingToken,
                                String executionKey, String terminationReason, Map<String, Object> usage, String traceDigest) { }
    public record ExecutionReceipt(String executionId, boolean idempotentReplay) { }
    private record TaskRow(String id, String researchRunId, String taskKey, String idempotencyKey, String status,
                           int leaseEpoch, long fencingToken, String workerInstanceId, Instant leaseExpiresAt,
                           String targetCellsJson, String budgetJson, String role, String entityId, String branchId,
                           int planRevision, int entitySetVersion, String targetBindingsJson, String executionContextJson,
                           String snapshotSchemaVersion, String snapshotDigest, String logicalTaskKey,
                           String quorumGroupKey, int candidateQuorum, int candidateSlot, String workspaceId,
                           boolean leaseValid) { }
    private record CanonicalSnapshot(String json, String digest) { }
    private record RunGuard(String status, String executionMode) {
        boolean runnableIncremental() {
            return "INCREMENTAL_V1".equals(executionMode)
                    && !Set.of("COMPLETED", "FAILED", "CANCELLED").contains(status);
        }
    }

    private static final class BudgetSnapshotAdapter {
        private static void settleIfReserved(ResearchBudgetAndCheckpointService budgetService, String taskId, String executionKey,
                                             String runId, Map<String, Object> usage) {
            Map<String, Long> normalized = new java.util.LinkedHashMap<>();
            usage.forEach((key, value) -> {
                if (!(value instanceof Number number)) throw new BusinessException("RESEARCH_BUDGET_INVALID", "Execution usage must be numeric");
                normalized.put(key, number.longValue());
            });
            budgetService.settleForExecution(runId, taskId, executionKey, Map.copyOf(normalized));
        }
    }
}
