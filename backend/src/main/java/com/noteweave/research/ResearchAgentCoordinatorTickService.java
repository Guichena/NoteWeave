package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.util.Set;
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
    private final ResearchAgentLifecycleService lifecycle;
    private final ResearchAgentRunnableWorkService runnableWork;
    private final ResearchAgentRoleWorkflowService roleWorkflow;
    private final ResearchAgentRunCompletionGate completionGate;
    private final ResearchAgentHonestReportService honestReports;
    private final ResearchGlobalConflictService globalConflictService;
    private final ResearchAgentConflictRepairService conflictRepairService;

    public ResearchAgentCoordinatorTickService(ResearchAgentCoordinatorSnapshotService snapshots,
                                               ResearchAgentTaskCoordinatorService coordinator,
                                               ResearchAgentCoordinatorRecoveryService recovery,
                                               ResearchAgentCoordinatorTickFaultInjector faultInjector,
                                               ResearchAgentIncrementalFinalizationService finalization,
                                               ResearchAgentRolloutGuard rolloutGuard,
                                               ResearchAgentLifecycleService lifecycle,
                                               ResearchAgentRunnableWorkService runnableWork,
                                               ResearchAgentRoleWorkflowService roleWorkflow,
                                               ResearchAgentRunCompletionGate completionGate,
                                               ResearchAgentHonestReportService honestReports,
                                               ResearchGlobalConflictService globalConflictService,
                                               ResearchAgentConflictRepairService conflictRepairService) {
        this.snapshots = snapshots;
        this.coordinator = coordinator;
        this.recovery = recovery;
        this.faultInjector = faultInjector;
        this.finalization = finalization;
        this.rolloutGuard = rolloutGuard;
        this.lifecycle = lifecycle;
        this.runnableWork = runnableWork;
        this.roleWorkflow = roleWorkflow;
        this.completionGate = completionGate;
        this.honestReports = honestReports;
        this.globalConflictService = globalConflictService;
        this.conflictRepairService = conflictRepairService;
    }

    @Transactional
    public TickReceipt tick(String runId, String coordinatorInstanceId) {
        if (coordinatorInstanceId == null || coordinatorInstanceId.isBlank()) {
            throw new BusinessException("RESEARCH_AGENT_COORDINATOR_TICK_INVALID", "Coordinator instance id is required");
        }
        // DR-303: re-adjudicate cross-source conflicts against the canonical ledger *before* taking the
        // snapshot, so a conflict demotion is visible to readyForFinalization()/nonFinalizableCellCount
        // and the terminal gate can never finalize a Run over a mutually-exclusive cell.
        globalConflictService.adjudicateRun(runId);
        // DR-304: dispatch (or explicitly exhaust) the bounded counterfactual repair for every still
        // open conflict, still before the snapshot so the tick's own branches see the dispatched task.
        conflictRepairService.advance(runId);
        ResearchAgentCoordinatorSnapshotService.Snapshot snapshot = snapshots.snapshot(runId);
        if (snapshot.activeTaskCount() > 0) {
            if (!runnableWork.enabledForRun(runId)) return new TickReceipt("ACTIVE_NOOP", snapshot, null);
            ResearchAgentRunnableWorkService.RefillReceipt refill = runnableWork.refill(snapshot);
            return new TickReceipt(refill.outcome(), snapshot, refill.taskization());
        }
        if (snapshot.taskCount() == 0) {
            if (!rolloutGuard.evaluate().allowsInitialWave()) {
                return new TickReceipt("ROLLOUT_PAUSED", snapshot, null);
            }
            ResearchAgentTaskCoordinatorService.CoordinatorReceipt receipt = coordinator.planAndEnqueueForWave(runId, 1);
            faultInjector.checkpoint(ResearchAgentCoordinatorTickFaultInjector.Stage.AFTER_INITIAL_TASKIZATION);
            if (receipt.createdTaskCount() > 0 || receipt.idempotentReplayCount() > 0) {
                return new TickReceipt("INITIAL_WAVE_TASKIZED", snapshot, receipt);
            }
            // M4-A2: the bootstrap scheduled nothing, so this Run is not a brand-new one being
            // guided - every remaining Cell is already settled, blocked by an open decision, or a
            // conflict. Returning here used to shadow every terminal decision below and spin
            // forever, because taskCount()==0 is also true for a hydrated ledger and for a ledger
            // whose Cells are all CONFLICTED / CONFLICT_EXHAUSTED. Re-read the snapshot and fall
            // through to the shared terminal decision instead of reporting a no-op as progress.
            snapshot = snapshots.snapshot(runId);
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
            if (!taskized) {
                // Tasks that only failed to find usable evidence, with the bounded repair budget
                // spent, are the same honest evidence outcome as the verifier-only case below: the
                // verified cells still deserve a report.  Runtime failures (lease exhaustion,
                // delivery errors) and an empty counterfactual source scope still fail the Run.
                boolean evidenceOnlyStop = snapshot.failedTaskCount() > 0
                        && receipt.decisionKind() != ResearchAgentRepairStopPolicy.DecisionKind.COUNTERFACTUAL
                        && receipt.decisionKind() != ResearchAgentRepairStopPolicy.DecisionKind.STOPPED_CANCELLED
                        && completionGate.failedTasksAreEvidenceOutcomes(runId);
                if (snapshot.failedTaskCount() > 0 && !evidenceOnlyStop) {
                    String terminalReason = receipt.decisionKind() == ResearchAgentRepairStopPolicy.DecisionKind.COUNTERFACTUAL
                            ? "RESEARCH_AGENT_COUNTERFACTUAL_SOURCE_SCOPE_EMPTY"
                            : "RESEARCH_AGENT_" + receipt.decisionKind().name();
                    lifecycle.failRun(runId, terminalReason);
                    return new TickReceipt(outcome, snapshot, receipt.taskization());
                }
                // A verifier-only repair can legitimately have no safe target after the bounded
                // per-cell budget is spent.  That is an honest evidence outcome, not an
                // infrastructure failure.  Send it through the same completion authority used by
                // the terminal barrier so conflict reason codes and limitations are persisted.
                ResearchAgentRunCompletionGate.CompletionDecision decision = completionGate.evaluateForRun(runId);
                completionGate.recordDecision(runId, decision);
                if (decision.terminalState().isInfrastructureFailure()) {
                    lifecycle.failRun(runId, completionGate.infrastructureTerminalReason(decision));
                    return new TickReceipt("RUN_FAILED_INFRASTRUCTURE", snapshot, receipt.taskization());
                }
                honestReports.renderAndPersist(runId, decision);
                lifecycle.completeRun(runId, decision.terminalState().name());
                return new TickReceipt(evidenceOnlyStop ? "FAILED_WAVE_STOPPED_COMPLETED" : "VERIFIER_REPAIR_STOPPED_COMPLETED",
                        snapshot, receipt.taskization());
            }
            return new TickReceipt(outcome, snapshot, receipt.taskization());
        }
        if (snapshot.readyForFinalization()) {
            ResearchAgentRoleWorkflowService.WorkflowReceipt workflow =
                    roleWorkflow.advance(runId, snapshot.currentWaveNo());
            if (!Set.of("FALLBACK_READY", "SYNTHESIS_READY").contains(workflow.outcome())) {
                return new TickReceipt(workflow.outcome(), snapshot, workflow.taskization());
            }
            finalization.finalizeIncrementalRun(runId);
            // DR-305: record the verified business terminal state for the fully converged path too,
            // so every terminal Run carries an explicit completion decision.
            completionGate.recordDecision(runId, completionGate.evaluateForRun(runId));
            return new TickReceipt("RUN_FINALIZED", snapshot, null);
        }
        if (snapshot.nonFinalizableCellCount() > 0 && runnableWork.enabledForRun(runId)) {
            ResearchAgentRunnableWorkService.RefillReceipt refill = runnableWork.refill(snapshot);
            if (refill.taskization() != null || "ACTIVE_BARRIER_PENDING".equals(refill.outcome())) {
                return new TickReceipt(refill.outcome(), snapshot, refill.taskization());
            }
        }
        // DR-305 / M4-A2: reaching this point means no task is active, no wave is recoverable, the
        // ledger is not ready for the verified-finalization path, and refill has nothing to
        // schedule. The RunCompletionGate — not the opaque RESEARCH_AGENT_TERMINAL_BARRIER_UNSATISFIED
        // reason — is the only authority for that state. The former `taskCount() > 0` guard is
        // deliberately gone: it excluded exactly the taskCount()==0 Runs (a hydrated ledger, an
        // all-conflict ledger) that hung forever, and a Run that never created a task is still a
        // Run that must terminate honestly. Missing evidence completes honestly; only a real
        // infrastructure fault fails the Run.
        ResearchAgentRunCompletionGate.CompletionDecision decision = completionGate.evaluateForRun(runId);
        completionGate.recordDecision(runId, decision);
        if (decision.terminalState().isInfrastructureFailure()) {
            lifecycle.failRun(runId, completionGate.infrastructureTerminalReason(decision));
            return new TickReceipt("RUN_FAILED_INFRASTRUCTURE", snapshot, null);
        }
        honestReports.renderAndPersist(runId, decision);
        lifecycle.completeRun(runId, decision.terminalState().name());
        return new TickReceipt("RUN_COMPLETED", snapshot, null);
    }

    public record TickReceipt(String outcome, ResearchAgentCoordinatorSnapshotService.Snapshot snapshot,
                              ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskization) { }
}
