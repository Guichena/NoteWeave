package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authorizes one tool permit from a locked, immutable task snapshot. */
@Service
public class ResearchAgentPermitService {

    private static final Set<String> SNAPSHOT_SCHEMAS = Set.of(
            ResearchAgentTaskSnapshotCanonicalizer.SCHEMA_V1,
            ResearchAgentTaskSnapshotCanonicalizer.SCHEMA_V2,
            ResearchAgentTaskSnapshotCanonicalizer.SCHEMA_V3);
    private static final Map<String, Set<String>> ROLE_TOOLS = Map.of(
            "DEEP_CELL", Set.of("search", "fetch", "read", "extract", "archive"),
            "WIDE_DISCOVERY", Set.of("search", "fetch", "read", "extract", "archive"),
            "COUNTERFACTUAL", Set.of("search", "fetch", "read", "extract", "archive"),
            "EVIDENCE_AUDIT", Set.of("fetch", "read", "extract", "archive"),
            "SYNTHESIS", Set.of("extract")
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentTaskSnapshotCanonicalizer snapshotCanonicalizer;
    private final ResearchAgentRateLimitService rateLimitService;
    private final MeterRegistry meters;

    public ResearchAgentPermitService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentTaskSnapshotCanonicalizer snapshotCanonicalizer,
            ResearchAgentRateLimitService rateLimitService,
            MeterRegistry meters
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.snapshotCanonicalizer = snapshotCanonicalizer;
        this.rateLimitService = rateLimitService;
        this.meters = meters;
    }

    @Transactional
    public PermitReceipt requirePermit(PermitCommand command) {
        validateCommand(command);
        String runId = jdbcTemplate.query("""
                select research_run_id from research_agent_task where id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, command.taskId());
        if (runId == null) throw staleLease();

        RunGuard run = jdbcTemplate.query("""
                select status, agent_execution_mode from research_run where id = ? for update
                """, rs -> rs.next() ? new RunGuard(rs.getString(1), rs.getString(2)) : null, runId);
        PermitTask task = jdbcTemplate.query("""
                select rat.id, rat.research_run_id, rr.workspace_id, rat.status, rat.worker_instance_id,
                    rat.lease_epoch, rat.fencing_token, rat.role, rat.entity_id, rat.branch_id,
                    rat.plan_revision, rat.entity_set_version, rat.target_bindings_json, rat.budget_json,
                    rat.execution_context_json, rat.snapshot_schema_version, rat.snapshot_digest,
                    rat.logical_task_key, rat.quorum_group_key, rat.candidate_quorum, rat.candidate_slot,
                    (rat.lease_expires_at > current_timestamp) as lease_valid
                from research_agent_task rat
                join research_run rr on rr.id = rat.research_run_id
                where rat.id = ? and rat.research_run_id = ?
                for update
                """, rs -> rs.next() ? new PermitTask(
                        rs.getString("id"),
                        rs.getString("research_run_id"),
                        rs.getString("workspace_id"),
                        rs.getString("status"),
                        rs.getString("worker_instance_id"),
                        rs.getInt("lease_epoch"),
                        rs.getLong("fencing_token"),
                        rs.getString("role"),
                        rs.getString("entity_id"),
                        rs.getString("branch_id"),
                        rs.getInt("plan_revision"),
                        rs.getInt("entity_set_version"),
                        rs.getString("target_bindings_json"),
                        rs.getString("budget_json"),
                        rs.getString("execution_context_json"),
                        rs.getString("snapshot_schema_version"),
                        rs.getString("snapshot_digest"),
                        rs.getString("logical_task_key"),
                        rs.getString("quorum_group_key"),
                        rs.getInt("candidate_quorum"),
                        rs.getInt("candidate_slot"),
                        rs.getBoolean("lease_valid")) : null,
                command.taskId(), runId);

        if (run == null || task == null || !run.runnableIncremental()
                || !("CLAIMED".equals(task.status()) || "RUNNING".equals(task.status()))
                || !command.workerInstanceId().equals(task.workerInstanceId())
                || command.leaseEpoch() != task.leaseEpoch()
                || command.fencingToken() != task.fencingToken()
                || !task.leaseValid()) {
            throw staleLease();
        }
        if (!SNAPSHOT_SCHEMAS.contains(task.snapshotSchemaVersion())) throw invalidSnapshot();

        ResearchAgentTaskSnapshotCanonicalizer.CanonicalSnapshot canonical = snapshotCanonicalizer.canonicalize(
                new ResearchAgentTaskSnapshotCanonicalizer.SnapshotInput(
                        task.snapshotSchemaVersion(), task.id(), task.runId(), task.workspaceId(), task.role(),
                        task.entityId(), task.branchId(),
                        task.planRevision(), task.entitySetVersion(), task.leaseEpoch(), task.fencingToken(),
                        task.targetBindingsJson(), task.budgetJson(), task.executionContextJson(),
                        task.logicalTaskKey(), task.quorumGroupKey(), task.candidateQuorum(), task.candidateSlot(),
                        task.candidateQuorum() == 2));
        if (!canonical.digest().equals(task.snapshotDigest())) throw invalidSnapshot();

        Set<String> allowedTools = ROLE_TOOLS.get(task.role());
        if (allowedTools == null || !allowedTools.contains(command.toolIdentity())) {
            meter("tool_forbidden");
            throw new BusinessException(
                    "RESEARCH_AGENT_PERMIT_TOOL_FORBIDDEN",
                    "The requested tool is not allowed for the authoritative task role");
        }
        String providerKey = providerKey(task.executionContextJson());
        meter("authorized");
        // Archiving does not invoke an external provider.  It must share the exact
        // authoritative lease check above, but must not consume provider quota.
        if (!"archive".equals(command.toolIdentity())) {
            rateLimitService.requirePermit(new ResearchAgentRateLimitService.PermitRequest(
                    providerKey, task.workspaceId(), task.runId(), task.role()));
        }
        return new PermitReceipt("GRANTED", command.toolIdentity());
    }

    private void validateCommand(PermitCommand command) {
        if (command == null || blank(command.taskId()) || blank(command.workerInstanceId())
                || blank(command.toolIdentity()) || command.leaseEpoch() < 1 || command.fencingToken() < 1
                || command.taskId().length() > 160 || command.workerInstanceId().length() > 160
                || command.toolIdentity().length() > 32) {
            meter("invalid");
            throw new BusinessException("RESEARCH_AGENT_PERMIT_INVALID", "Research agent permit request is invalid");
        }
    }

    private String providerKey(String executionContextJson) {
        try {
            Map<String, Object> context = objectMapper.readValue(executionContextJson, new TypeReference<>() { });
            Object provider = context.get("provider_key");
            if (provider instanceof String value && !blank(value) && value.length() <= 160) return value;
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            // The canonicalizer has already rejected malformed snapshot JSON.
        }
        throw invalidSnapshot();
    }

    private BusinessException staleLease() {
        meter("stale_lease");
        return new BusinessException(
                "RESEARCH_AGENT_TASK_STALE_LEASE",
                "Research agent lease is stale or owned by another worker");
    }

    private BusinessException invalidSnapshot() {
        meter("snapshot_invalid");
        return new BusinessException(
                "RESEARCH_AGENT_TASK_SNAPSHOT_INVALID",
                "Research agent task snapshot is invalid");
    }

    private void meter(String result) {
        meters.counter("noteweave.research.agent.permit.authorization", "result", result).increment();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record PermitCommand(
            String taskId,
            String workerInstanceId,
            int leaseEpoch,
            long fencingToken,
            String toolIdentity
    ) { }

    public record PermitReceipt(String status, String toolIdentity) { }

    private record RunGuard(String status, String executionMode) {
        boolean runnableIncremental() {
            return "INCREMENTAL_V1".equals(executionMode)
                    && !Set.of("COMPLETED", "FAILED", "CANCELLED").contains(status);
        }
    }

    private record PermitTask(
            String id,
            String runId,
            String workspaceId,
            String status,
            String workerInstanceId,
            int leaseEpoch,
            long fencingToken,
            String role,
            String entityId,
            String branchId,
            int planRevision,
            int entitySetVersion,
            String targetBindingsJson,
            String budgetJson,
            String executionContextJson,
            String snapshotSchemaVersion,
            String snapshotDigest,
            String logicalTaskKey,
            String quorumGroupKey,
            int candidateQuorum,
            int candidateSlot,
            boolean leaseValid
    ) { }
}
