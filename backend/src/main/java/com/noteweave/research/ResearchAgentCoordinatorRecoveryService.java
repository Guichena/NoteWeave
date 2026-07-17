package com.noteweave.research;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Builds a failed-wave recovery command exclusively from the locked run snapshot. */
@Service
public class ResearchAgentCoordinatorRecoveryService {
    private final JdbcTemplate jdbcTemplate;
    private final ResearchAgentRepairAdvancementService repairs;

    public ResearchAgentCoordinatorRecoveryService(JdbcTemplate jdbcTemplate, ResearchAgentRepairAdvancementService repairs) {
        this.jdbcTemplate = jdbcTemplate;
        this.repairs = repairs;
    }

    public ResearchAgentRepairAdvancementService.RepairReceipt recoverRepairableWave(
            ResearchAgentCoordinatorSnapshotService.Snapshot snapshot, String coordinatorInstanceId) {
        int waveNo = snapshot.currentWaveNo();
        int planRevision = integer("select coalesce(max(plan_revision), 0) from research_cell where research_run_id = ?", snapshot.runId());
        int entitySetVersion = integer("select coalesce(max(entity_set_version), 0) from research_cell where research_run_id = ?", snapshot.runId());
        long taskHighWater = count("select count(*) from research_agent_task where research_run_id = ?", snapshot.runId());
        long candidateHighWater = count("select count(*) from research_agent_candidate where research_run_id = ?", snapshot.runId());
        long mergeHighWater = count("select count(*) from research_cell_merge where research_run_id = ?", snapshot.runId());
        String advanceKey = "auto-repair:wave:" + waveNo + ":checkpoint:" + snapshot.checkpointSeq();
        return repairs.advanceAndTaskize(new ResearchAgentRepairAdvancementService.RepairAdvanceCommand(
                snapshot.runId(), advanceKey, coordinatorInstanceId, snapshot.checkpointSeq(), waveNo, waveNo + 1,
                Math.max(1, waveNo), planRevision, entitySetVersion,
                "coordinator-ledger:" + advanceKey, taskHighWater, candidateHighWater, mergeHighWater,
                Map.of("source", "coordinator_snapshot", "failed_task_count", snapshot.failedTaskCount(),
                        "verifier_repair_required_count", snapshot.verifierRepairRequiredCount()),
                Map.of("decision_source", "server_derived_repair_gap", "failed_wave_no", waveNo)));
    }

    private int integer(String sql, String runId) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, runId);
        return value == null ? 0 : value;
    }

    private long count(String sql, String runId) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, runId);
        return value == null ? 0 : value;
    }
}
