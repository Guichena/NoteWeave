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
    private final ResearchPlanningService planning;
    private final ResearchDiscoveryLoopService discoveryLoop;

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
                                               ResearchAgentConflictRepairService conflictRepairService,
                                               ResearchPlanningService planning,
                                               ResearchDiscoveryLoopService discoveryLoop) {
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
        this.planning = planning;
        this.discoveryLoop = discoveryLoop;
    }

    @Transactional
    public TickReceipt tick(String runId, String coordinatorInstanceId) {
        if (coordinatorInstanceId == null || coordinatorInstanceId.isBlank()) {
            throw new BusinessException("RESEARCH_AGENT_COORDINATOR_TICK_INVALID", "Coordinator instance id is required");
        }
        // 规划阶段：模型规划表格、等待用户确认期间不下发任何深挖任务
        ResearchPlanningService.GateOutcome plan = planning.advance(runId);
        if (plan.waiting()) {
            return new TickReceipt(plan.outcome(), snapshots.snapshot(runId), plan.taskization());
        }
        discoveryLoop.initialize(runId);
        // DR-303: re-adjudicate cross-source conflicts against the canonical ledger *before* taking the
        // snapshot, so a conflict demotion is visible to readyForFinalization()/nonFinalizableCellCount
        // and the terminal gate can never finalize a Run over a mutually-exclusive cell.
        globalConflictService.adjudicateRun(runId);
        // 已结束任务留下的单元格绑定会让后续任务认领失败，先清掉再判断下一步
        lifecycle.releaseOrphanedCellBindings(runId);
        // 收到消息时还不能认领的任务会被 Worker 跳过，投递后长时间无人认领的重新投递一次
        lifecycle.redriveStrandedDeliveries(runId);
        ResearchAgentCoordinatorSnapshotService.Snapshot auditSnapshot = snapshots.snapshot(runId);
        if (auditSnapshot.activeTaskCount() == 0) {
            var audit = roleWorkflow.advancePendingAudit(runId, auditSnapshot.currentWaveNo());
            if ("AUDIT_TASKIZED".equals(audit.outcome())) return new TickReceipt(audit.outcome(), snapshots.snapshot(runId), audit.taskization());
        }
        // DR-304: dispatch (or explicitly exhaust) the bounded counterfactual repair for every still
        // open conflict, still before the snapshot so the tick's own branches see the dispatched task.
        conflictRepairService.advance(runId);
        ResearchAgentCoordinatorSnapshotService.Snapshot snapshot = snapshots.snapshot(runId);
        if (snapshot.activeTaskCount() > 0) {
            if (discoveryLoop.enabled(runId)) return new TickReceipt("ACTIVE_NOOP", snapshot, null);
            if (!runnableWork.enabledForRun(runId)) return new TickReceipt("ACTIVE_NOOP", snapshot, null);
            ResearchAgentRunnableWorkService.RefillReceipt refill = runnableWork.refill(snapshot);
            return new TickReceipt(refill.outcome(), snapshot, refill.taskization());
        }
        if (snapshot.taskCount() == 0 && discoveryLoop.enabled(runId) && !rolloutGuard.evaluate().allowsInitialWave())
            return new TickReceipt("ROLLOUT_PAUSED", snapshot, null);
        if (snapshot.taskCount() == 0 && !discoveryLoop.enabled(runId)) {
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
        // 合并任务里没拿到结论的单元格也走同一套补查；每个单元格只补一次，补查后不再计入。
        // 模型或搜索服务不可用导致的缺口补查也没用，交给后面的收尾判断按基础设施故障处理
        boolean gapRepairDue = snapshot.gapRepairCellCount() > 0
                && !completionGate.evaluateForRun(runId).terminalState().isInfrastructureFailure();
        if (snapshot.failedTaskCount() > 0 || snapshot.verifierRepairRequiredCount() > 0 || gapRepairDue) {
            ResearchAgentRepairAdvancementService.RepairReceipt receipt = recovery.recoverRepairableWave(
                    snapshot, coordinatorInstanceId.trim());
            faultInjector.checkpoint(ResearchAgentCoordinatorTickFaultInjector.Stage.AFTER_FAILED_WAVE_RECOVERY);
            boolean taskized = receipt.taskization().createdTaskCount() > 0
                    || receipt.taskization().idempotentReplayCount() > 0;
            String outcome = snapshot.failedTaskCount() > 0
                    ? (taskized ? "FAILED_WAVE_REPAIR_TASKIZED" : "FAILED_WAVE_STOPPED")
                    : snapshot.verifierRepairRequiredCount() > 0
                    ? (taskized ? "VERIFIER_REPAIR_TASKIZED" : "VERIFIER_REPAIR_STOPPED")
                    : (taskized ? "GAP_REPAIR_TASKIZED" : "GAP_REPAIR_STOPPED");
            boolean gapOnly = snapshot.failedTaskCount() == 0 && snapshot.verifierRepairRequiredCount() == 0;
            if (!taskized && gapOnly) {
                // 缺口补查没派出任务（预算用完或没有可换的来源）时，照常走后面的宽搜索与收尾判断
                snapshot = snapshots.snapshot(runId);
            } else if (!taskized) {
                // Tasks that only failed to find usable evidence, with the bounded repair budget
                // spent, are the same honest evidence outcome as the verifier-only case below: the
                // verified cells still deserve a report.  Runtime failures (lease exhaustion,
                // delivery errors) and an empty counterfactual source scope still fail the Run.
                boolean evidenceOnlyStop = snapshot.failedTaskCount() > 0
                        && receipt.decisionKind() != ResearchAgentRepairStopPolicy.DecisionKind.COUNTERFACTUAL
                        && receipt.decisionKind() != ResearchAgentRepairStopPolicy.DecisionKind.STOPPED_CANCELLED
                        && completionGate.failedTasksAreEvidenceOutcomes(runId);
                if (snapshot.failedTaskCount() > 0 && !evidenceOnlyStop && !discoveryLoop.budgetStopped(runId)) {
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
                TickReceipt waiting = awaitPartialSynthesis(runId, snapshot, decision);
                if (waiting != null) return waiting;
                honestReports.renderAndPersist(runId, decision);
                lifecycle.completeRun(runId, decision.terminalState().name());
                return new TickReceipt(evidenceOnlyStop ? "FAILED_WAVE_STOPPED_COMPLETED" : "VERIFIER_REPAIR_STOPPED_COMPLETED",
                        snapshot, receipt.taskization());
            } else {
                return new TickReceipt(outcome, snapshot, receipt.taskization());
            }
        }
        var discovery = discoveryLoop.advance(runId, snapshot.currentWaveNo());
        if (!Set.of("DISABLED", "STOPPED").contains(discovery.outcome()))
            return new TickReceipt(discovery.outcome(), snapshots.snapshot(runId), discovery.taskization());
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
        if (snapshot.nonFinalizableCellCount() > 0 && !discoveryLoop.enabled(runId) && runnableWork.enabledForRun(runId)) {
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
        TickReceipt waiting = awaitPartialSynthesis(runId, snapshot, decision);
        if (waiting != null) return waiting;
        honestReports.renderAndPersist(runId, decision);
        lifecycle.completeRun(runId, decision.terminalState().name());
        return new TickReceipt("RUN_COMPLETED", snapshot, null);
    }

    /**
     * 带局限收尾时，先让已核验的单元格经过综合撰写再出报告，否则报告只是逐条罗列引用。
     * 综合任务已派出或仍在进行时返回等待回执，下一轮再判断；其余情况返回 null，照常收尾。
     */
    private TickReceipt awaitPartialSynthesis(String runId, ResearchAgentCoordinatorSnapshotService.Snapshot snapshot,
                                              ResearchAgentRunCompletionGate.CompletionDecision decision) {
        // 完成判定只描述核验程度，不决定报告有没有回答：证据不足时也用已核验的单元格成文，
        // 没有任何已核验单元格时 advancePartialSynthesis 会退回模板
        if (decision.terminalState() != ResearchAgentRunCompletionGate.CompletionTerminalState.COMPLETED_WITH_LIMITATIONS
                && decision.terminalState() != ResearchAgentRunCompletionGate.CompletionTerminalState.INSUFFICIENT_EVIDENCE) {
            return null;
        }
        ResearchAgentRoleWorkflowService.WorkflowReceipt workflow = roleWorkflow.advancePartialSynthesis(
                runId, snapshot.currentWaveNo(), honestReports.limitationLines(runId, decision));
        if (Set.of("SYNTHESIS_TASKIZED", "SYNTHESIS_PENDING").contains(workflow.outcome())) {
            return new TickReceipt("PARTIAL_" + workflow.outcome(), snapshot, workflow.taskization());
        }
        return null;
    }

    public record TickReceipt(String outcome, ResearchAgentCoordinatorSnapshotService.Snapshot snapshot,
                              ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskization) { }
}
