package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Refills only runnable, unbound cells while preserving per-cell and stage barriers. */
@Service
class ResearchAgentRunnableWorkService {
    private static final String STAGE = "CELL_RESEARCH";
    /** Shared with the hydrator so a restored barrier cannot drift from the refill barrier. */
    static final String STAGE_VERSION = "research-run-stage.v1";
    private static final String DIGEST_DOMAIN = "research-run-stage-barrier.v1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentTaskCoordinatorService coordinator;
    private final ResearchAgentCompletionCanonicalizer canonicalizer;
    private final boolean enabled;
    private final ResearchAgentFeatureFlagService featureFlags;

    ResearchAgentRunnableWorkService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentTaskCoordinatorService coordinator,
            ResearchAgentCompletionCanonicalizer canonicalizer,
            ResearchAgentFeatureFlagService featureFlags,
            @Value("${noteweave.research.runnable-work-v2:false}") boolean enabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.coordinator = coordinator;
        this.canonicalizer = canonicalizer;
        this.featureFlags = featureFlags;
        this.enabled = enabled;
    }

    RefillReceipt refill(
            ResearchAgentCoordinatorSnapshotService.Snapshot snapshot
    ) {
        if (!enabledForRun(snapshot.runId())) return new RefillReceipt("LEGACY_ACTIVE_NOOP", null, 0, 0);
        int runnable = countRunnable(snapshot.runId());
        int blocked = countBlocked(snapshot.runId());
        int revision = snapshot.activeTaskCount() > 0
                ? Math.max(1, snapshot.currentWaveNo())
                : Math.max(1, snapshot.currentWaveNo() + 1);
        if (runnable == 0) {
            String status = snapshot.activeTaskCount() > 0 || blocked > 0 ? "BARRIER_PENDING" : "SETTLED";
            persistStage(snapshot, revision, status, 0, blocked);
            return new RefillReceipt(
                    "BARRIER_PENDING".equals(status) ? "ACTIVE_BARRIER_PENDING" : "STAGE_ADVANCED",
                    null, runnable, blocked);
        }
        ResearchAgentTaskCoordinatorService.CoordinatorReceipt receipt =
                coordinator.planAndEnqueueForWave(snapshot.runId(), revision);
        persistStage(snapshot, revision, "ACTIVE", receipt.createdTaskCount() + receipt.idempotentReplayCount(), blocked);
        return new RefillReceipt("ACTIVE_REFILL_TASKIZED", receipt, runnable, blocked);
    }

    boolean enabled() {
        return enabled;
    }

    boolean enabledForRun(String runId) {
        return enabled && featureFlags.enabledForRun(runId, ResearchAgentFeatureFlagService.RUNNABLE_WORK);
    }

    private int countRunnable(String runId) {
        return runnableCellCount(jdbcTemplate, runId);
    }

    private int countBlocked(String runId) {
        return blockedCellCount(jdbcTemplate, runId);
    }

    /**
     * Cell ledger view the barrier status is derived from. Package-private static so the
     * canonical-ledger hydrator recomputes the descendant barrier from the exact same query
     * instead of mirroring it.
     */
    static int runnableCellCount(JdbcTemplate jdbcTemplate, String runId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from research_cell cell
                where cell.research_run_id = ?
                  and cell.cell_status in ('GAP', 'STALE')
                  and cell.active_task_id is null
                  and not exists (
                    select 1 from research_verifier_decision decision
                    where decision.research_run_id = cell.research_run_id
                      and decision.target_id = cell.cell_key
                      and decision.decision_status = 'OPEN'
                      and decision.decision_type in (
                        'QUORUM_REPAIR_REQUIRED', 'EVIDENCE_AUDIT_REPAIR_REQUIRED', 'EVIDENCE_AUDIT_BLOCKED'
                      )
                  )
                """, Integer.class, runId);
        return count == null ? 0 : count;
    }

    static int blockedCellCount(JdbcTemplate jdbcTemplate, String runId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(distinct cell.id)
                from research_cell cell
                join research_verifier_decision decision
                  on decision.research_run_id = cell.research_run_id and decision.target_id = cell.cell_key
                where cell.research_run_id = ? and decision.decision_status = 'OPEN'
                  and decision.decision_type in (
                    'QUORUM_REPAIR_REQUIRED', 'EVIDENCE_AUDIT_REPAIR_REQUIRED', 'EVIDENCE_AUDIT_BLOCKED'
                  )
                """, Integer.class, runId);
        return count == null ? 0 : count;
    }

    /** Single barrier-digest authority, shared with the hydrator. */
    static String barrierDigest(ResearchAgentCompletionCanonicalizer canonicalizer, Map<String, Object> barrier) {
        return canonicalizer.domainSeparatedDigest(DIGEST_DOMAIN, barrier);
    }

    private void persistStage(
            ResearchAgentCoordinatorSnapshotService.Snapshot snapshot,
            int revision,
            String status,
            int expectedTaskCount,
            int blockerCount
    ) {
        Integer settled = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task
                where research_run_id = ? and wave_no = ?
                  and status in ('SUBMITTED', 'FAILED', 'CANCELLED')
                """, Integer.class, snapshot.runId(), revision);
        Map<String, Object> barrier = new LinkedHashMap<>();
        barrier.put("run_id", snapshot.runId());
        barrier.put("stage", STAGE);
        barrier.put("stage_revision", revision);
        barrier.put("status", status);
        barrier.put("expected_task_count", expectedTaskCount);
        barrier.put("settled_task_count", settled == null ? 0 : settled);
        barrier.put("blocker_count", blockerCount);
        barrier.put("checkpoint_seq", snapshot.checkpointSeq());
        String digest = barrierDigest(canonicalizer, barrier);
        jdbcTemplate.update("""
                insert into research_run_stage(
                    id, research_run_id, stage, stage_revision, status, barrier_digest,
                    expected_task_count, settled_task_count, blocker_count, stage_version, barrier_json)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on duplicate key update
                    status = values(status), barrier_digest = values(barrier_digest),
                    expected_task_count = greatest(expected_task_count, values(expected_task_count)),
                    settled_task_count = values(settled_task_count), blocker_count = values(blocker_count),
                    barrier_json = values(barrier_json), updated_at = current_timestamp
                """, Ids.newId(), snapshot.runId(), STAGE, revision, status, digest,
                expectedTaskCount, settled == null ? 0 : settled, blockerCount, STAGE_VERSION,
                Json.write(objectMapper, barrier));
    }

    record RefillReceipt(
            String outcome,
            ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskization,
            int runnableCellCount,
            int blockedCellCount
    ) { }
}
