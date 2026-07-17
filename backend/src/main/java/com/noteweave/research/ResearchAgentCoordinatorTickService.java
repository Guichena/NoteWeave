package com.noteweave.research;

import com.noteweave.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** First restart-safe coordinator tick: auto-opens a new incremental run exactly once. */
@Service
public class ResearchAgentCoordinatorTickService {
    private final ResearchAgentCoordinatorSnapshotService snapshots;
    private final ResearchAgentTaskCoordinatorService coordinator;
    private final ResearchAgentCoordinatorRecoveryService recovery;
    private final ResearchAgentCoordinatorTickFaultInjector faultInjector;
    private final ResearchAgentIncrementalFinalizationService finalization;
    private final ResearchAgentRolloutGuard rolloutGuard;

    public ResearchAgentCoordinatorTickService(ResearchAgentCoordinatorSnapshotService snapshots,
                                               ResearchAgentTaskCoordinatorService coordinator,
                                               ResearchAgentCoordinatorRecoveryService recovery,
                                               ResearchAgentCoordinatorTickFaultInjector faultInjector,
                                               ResearchAgentIncrementalFinalizationService finalization,
                                               ResearchAgentRolloutGuard rolloutGuard) {
        this.snapshots = snapshots;
        this.coordinator = coordinator;
        this.recovery = recovery;
        this.faultInjector = faultInjector;
        this.finalization = finalization;
        this.rolloutGuard = rolloutGuard;
    }

    @Transactional
    public TickReceipt tick(String runId, String coordinatorInstanceId) {
        if (coordinatorInstanceId == null || coordinatorInstanceId.isBlank()) {
            throw new BusinessException("RESEARCH_AGENT_COORDINATOR_TICK_INVALID", "Coordinator instance id is required");
        }
        ResearchAgentCoordinatorSnapshotService.Snapshot snapshot = snapshots.snapshot(runId);
        if (snapshot.activeTaskCount() > 0) return new TickReceipt("ACTIVE_NOOP", snapshot, null);
        if (snapshot.taskCount() == 0) {
            if (!rolloutGuard.evaluate().allowsInitialWave()) {
                return new TickReceipt("ROLLOUT_PAUSED", snapshot, null);
            }
            ResearchAgentTaskCoordinatorService.CoordinatorReceipt receipt = coordinator.planAndEnqueueForWave(runId, 1);
            faultInjector.checkpoint(ResearchAgentCoordinatorTickFaultInjector.Stage.AFTER_INITIAL_TASKIZATION);
            return new TickReceipt("INITIAL_WAVE_TASKIZED", snapshot, receipt);
        }
        if (snapshot.failedTaskCount() > 0 || snapshot.verifierRepairRequiredCount() > 0) {
            ResearchAgentRepairAdvancementService.RepairReceipt receipt = recovery.recoverRepairableWave(
                    snapshot, coordinatorInstanceId.trim());
            faultInjector.checkpoint(ResearchAgentCoordinatorTickFaultInjector.Stage.AFTER_FAILED_WAVE_RECOVERY);
            boolean taskized = receipt.taskization().createdTaskCount() > 0
                    || receipt.taskization().idempotentReplayCount() > 0;
            String outcome = snapshot.failedTaskCount() > 0
                    ? (taskized ? "FAILED_WAVE_REPAIR_TASKIZED" : "FAILED_WAVE_STOPPED")
                    : (taskized ? "VERIFIER_REPAIR_TASKIZED" : "VERIFIER_REPAIR_STOPPED");
            return new TickReceipt(outcome, snapshot, receipt.taskization());
        }
        if (snapshot.readyForFinalization()) {
            finalization.finalizeIncrementalRun(runId);
            return new TickReceipt("RUN_FINALIZED", snapshot, null);
        }
        return new TickReceipt("TERMINAL_BARRIER_PENDING", snapshot, null);
    }

    public record TickReceipt(String outcome, ResearchAgentCoordinatorSnapshotService.Snapshot snapshot,
                              ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskization) { }
}
