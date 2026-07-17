package com.noteweave.research;

import com.noteweave.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authoritative run-lock snapshot for the future recovery coordinator. */
@Service
public class ResearchAgentCoordinatorSnapshotService {
    private final JdbcTemplate jdbcTemplate;
    public ResearchAgentCoordinatorSnapshotService(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    // The snapshot is the coordinator's linearization point, so it intentionally
    // takes a write lock on research_run. Do not mark this transaction read-only:
    // MySQL deployments may reject SELECT ... FOR UPDATE in a read-only transaction.
    @Transactional
    public Snapshot snapshot(String runId) {
        String mode = jdbcTemplate.query("select agent_execution_mode from research_run where id = ? for update",
                rs -> rs.next() ? rs.getString(1) : null, runId);
        if (!"INCREMENTAL_V1".equals(mode)) throw new BusinessException("RESEARCH_AGENT_COORDINATOR_SNAPSHOT_INVALID", "Snapshot requires an incremental run");
        Integer checkpoint = jdbcTemplate.queryForObject("select coalesce(max(checkpoint_seq), 0) from research_agent_checkpoint where research_run_id = ?", Integer.class, runId);
        Integer wave = jdbcTemplate.queryForObject("select coalesce(max(wave_no), 1) from research_agent_task where research_run_id = ?", Integer.class, runId);
        Long taskCount = jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ?", Long.class, runId);
        Long active = jdbcTemplate.queryForObject("select count(*) from research_agent_task where research_run_id = ? and status in ('PENDING','CLAIMED','RUNNING','RETRY_WAIT','EXPIRED')", Long.class, runId);
        int currentWave = wave == null ? 1 : wave;
        Long failed = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task
                where research_run_id = ? and wave_no = ? and status = 'FAILED'
                """, Long.class, runId, currentWave);
        Long verifierRepairs = jdbcTemplate.queryForObject("""
                select count(*)
                from research_verifier_decision decision
                join research_cell cell
                  on cell.research_run_id = decision.research_run_id and cell.cell_key = decision.target_id
                where decision.research_run_id = ?
                  and decision.decision_scope = 'CELL'
                  and decision.decision_type = 'QUORUM_REPAIR_REQUIRED'
                  and decision.action_text = 'COUNTERFACTUAL_REPAIR'
                  and decision.decision_status = 'OPEN'
                  and cell.cell_status <> 'VERIFIED'
                """, Long.class, runId);
        Long verifiedCells = jdbcTemplate.queryForObject("""
                select count(*) from research_cell
                where research_run_id = ? and cell_status = 'VERIFIED'
                  and evidence_refs_json is not null and evidence_refs_json <> '[]'
                """, Long.class, runId);
        Long nonFinalizableCells = jdbcTemplate.queryForObject("""
                select count(*) from research_cell
                where research_run_id = ?
                  and (cell_status <> 'VERIFIED' or evidence_refs_json is null or evidence_refs_json = '[]')
                """, Long.class, runId);
        return new Snapshot(runId, checkpoint == null ? 0 : checkpoint, wave == null ? 1 : wave,
                taskCount == null ? 0 : taskCount, active == null ? 0 : active, failed == null ? 0 : failed,
                verifierRepairs == null ? 0 : verifierRepairs,
                verifiedCells == null ? 0 : verifiedCells, nonFinalizableCells == null ? 0 : nonFinalizableCells);
    }
    public record Snapshot(String runId, int checkpointSeq, int currentWaveNo, long taskCount,
                           long activeTaskCount, long failedTaskCount, long verifierRepairRequiredCount,
                           long verifiedCellCount, long nonFinalizableCellCount) {
        public boolean readyForFinalization() { return verifiedCellCount > 0 && nonFinalizableCellCount == 0; }
    }
}
