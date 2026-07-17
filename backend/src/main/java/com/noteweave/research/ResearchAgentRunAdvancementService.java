package com.noteweave.research;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MA4I durable advancement receipt. It deliberately does not taskize a new wave yet. */
@Service
public class ResearchAgentRunAdvancementService {

    private final JdbcTemplate jdbcTemplate;
    private final ResearchBudgetAndCheckpointService checkpoints;

    public ResearchAgentRunAdvancementService(JdbcTemplate jdbcTemplate, ResearchBudgetAndCheckpointService checkpoints) {
        this.jdbcTemplate = jdbcTemplate;
        this.checkpoints = checkpoints;
    }

    @Transactional
    public AdvanceReceipt advance(AdvanceCommand command) {
        validate(command);
        RunGuard run = lockRun(command.researchRunId());
        if (!"INCREMENTAL_V1".equals(run.executionMode())) {
            throw new BusinessException("RESEARCH_AGENT_ADVANCEMENT_MODE_INVALID", "Advancement requires INCREMENTAL_V1");
        }
        if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(run.status())) {
            throw new BusinessException("RESEARCH_AGENT_RUN_TERMINAL", "Cannot advance terminal run");
        }
        AdvancementRow replay = find(command.researchRunId(), command.advanceKey());
        if (replay != null) {
            if (replay.expectedCheckpointSeq() != command.expectedCheckpointSeq()
                    || !replay.decisionDigest().equals(command.decisionDigest())) {
                throw new BusinessException("RESEARCH_AGENT_ADVANCEMENT_IDEMPOTENCY_CONFLICT", "Advance key is bound to different content");
            }
            return new AdvanceReceipt(replay.id(), replay.checkpointId(), replay.checkpointSeq(), true);
        }
        int latest = latestCheckpointSeq(command.researchRunId());
        if (latest != command.expectedCheckpointSeq()) {
            throw new BusinessException("RESEARCH_AGENT_ADVANCEMENT_STALE_CURSOR", "Advancement predecessor checkpoint is stale");
        }
        ResearchBudgetAndCheckpointService.CheckpointReceipt checkpoint = checkpoints.appendCheckpoint(
                new ResearchBudgetAndCheckpointService.CheckpointCommand(
                        command.researchRunId(), command.waveNo(), command.roundNo(), command.planRevision(),
                        command.entitySetVersion(), command.ledgerHash(), command.taskHighWaterMark(),
                        command.candidateHighWaterMark(), command.mergeHighWaterMark(), command.budgetSummary(), command.summary()));
        String id = Ids.newId();
        jdbcTemplate.update("""
                insert into research_agent_run_advancement(
                    id, research_run_id, advance_key, coordinator_id, expected_checkpoint_seq, decision_digest,
                    checkpoint_id, checkpoint_seq, lease_epoch, fencing_token, lease_expires_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 1, 1, timestampadd(second, 30, current_timestamp))
                """, id, command.researchRunId(), command.advanceKey(), command.coordinatorId(),
                command.expectedCheckpointSeq(), command.decisionDigest(), checkpoint.checkpointId(), checkpoint.checkpointSeq());
        return new AdvanceReceipt(id, checkpoint.checkpointId(), checkpoint.checkpointSeq(), false);
    }

    private RunGuard lockRun(String runId) {
        RunGuard run = jdbcTemplate.query("select status, agent_execution_mode from research_run where id = ? for update",
                rs -> rs.next() ? new RunGuard(rs.getString(1), rs.getString(2)) : null, runId);
        if (run == null) throw new BusinessException("RESEARCH_AGENT_RUN_NOT_FOUND", "Research run does not exist");
        return run;
    }

    private AdvancementRow find(String runId, String advanceKey) {
        return jdbcTemplate.query("""
                select id, expected_checkpoint_seq, decision_digest, checkpoint_id, checkpoint_seq
                from research_agent_run_advancement where research_run_id = ? and advance_key = ?
                """, rs -> rs.next() ? new AdvancementRow(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getInt(5)) : null,
                runId, advanceKey);
    }

    private int latestCheckpointSeq(String runId) {
        Integer value = jdbcTemplate.queryForObject("select coalesce(max(checkpoint_seq), 0) from research_agent_checkpoint where research_run_id = ?", Integer.class, runId);
        return value == null ? 0 : value;
    }

    private void validate(AdvanceCommand command) {
        if (command == null || blank(command.researchRunId()) || blank(command.advanceKey()) || blank(command.coordinatorId())
                || blank(command.decisionDigest()) || blank(command.ledgerHash()) || command.expectedCheckpointSeq() < 0
                || command.waveNo() < 1 || command.roundNo() < 1 || command.planRevision() < 0 || command.entitySetVersion() < 0
                || command.taskHighWaterMark() < 0 || command.candidateHighWaterMark() < 0 || command.mergeHighWaterMark() < 0
                || command.budgetSummary() == null || command.summary() == null) {
            throw new BusinessException("RESEARCH_AGENT_ADVANCEMENT_INVALID", "Advancement command is invalid");
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record AdvanceCommand(String researchRunId, String advanceKey, String coordinatorId, int expectedCheckpointSeq,
                                 String decisionDigest, int waveNo, int roundNo, int planRevision, int entitySetVersion,
                                 String ledgerHash, long taskHighWaterMark, long candidateHighWaterMark,
                                 long mergeHighWaterMark, Map<String, Object> budgetSummary, Map<String, Object> summary) { }
    public record AdvanceReceipt(String advancementId, String checkpointId, int checkpointSeq, boolean idempotentReplay) { }
    private record RunGuard(String status, String executionMode) { }
    private record AdvancementRow(String id, int expectedCheckpointSeq, String decisionDigest, String checkpointId, int checkpointSeq) { }
}
