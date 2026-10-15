package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchAgentCoordinatorRolloutGuardTest {

    @Test
    void shouldPauseOnlyAnInitialWaveWhenTheRuntimeHealthGuardIsOpen() {
        var snapshots = mock(ResearchAgentCoordinatorSnapshotService.class);
        var coordinator = mock(ResearchAgentTaskCoordinatorService.class);
        var recovery = mock(ResearchAgentCoordinatorRecoveryService.class);
        var faults = mock(ResearchAgentCoordinatorTickFaultInjector.class);
        var finalization = mock(ResearchAgentIncrementalFinalizationService.class);
        var guard = mock(ResearchAgentRolloutGuard.class);
        var lifecycle = mock(ResearchAgentLifecycleService.class);
        var runnableWork = mock(ResearchAgentRunnableWorkService.class);
        var roleWorkflow = mock(ResearchAgentRoleWorkflowService.class);
        var completionGate = mock(ResearchAgentRunCompletionGate.class);
        var honestReports = mock(ResearchAgentHonestReportService.class);
        var globalConflictService = mock(ResearchGlobalConflictService.class);
        var conflictRepairService = mock(ResearchAgentConflictRepairService.class);
        var planning = mock(ResearchPlanningService.class);
        when(planning.advance("run-1")).thenReturn(new ResearchPlanningService.GateOutcome(false, "PLAN_READY", null));
        var snapshot = new ResearchAgentCoordinatorSnapshotService.Snapshot(
                "run-1", 0, 1, 0, 0, 0, 0, 0, 2);
        when(snapshots.snapshot("run-1")).thenReturn(snapshot);
        when(roleWorkflow.advancePendingAudit("run-1", 1)).thenReturn(new ResearchAgentRoleWorkflowService.WorkflowReceipt("NO_AUDIT_PENDING", null));
        when(guard.evaluate()).thenReturn(new ResearchAgentRolloutPolicy.Decision(
                ResearchAgentRolloutPolicy.Action.PAUSE_NEW_INITIAL_WAVES,
                List.of("DELIVERY_FAILURE_RATE_EXCEEDED"),
                Map.of("delivery_failure_rate", 0.25d)));
        var service = new ResearchAgentCoordinatorTickService(
                snapshots, coordinator, recovery, faults, finalization, guard, lifecycle,
                runnableWork, roleWorkflow, completionGate, honestReports,
                globalConflictService, conflictRepairService, planning, mock(ResearchDiscoveryLoopService.class));

        var receipt = service.tick("run-1", "coordinator-1");

        assertThat(receipt.outcome()).isEqualTo("ROLLOUT_PAUSED");
        verify(roleWorkflow).advancePendingAudit("run-1", 1);
        verifyNoInteractions(coordinator, recovery, faults, finalization, runnableWork);
        // 每次协调都会先清理已结束任务留下的单元格绑定，除此之外不做任何生命周期操作
        org.mockito.Mockito.verify(lifecycle).releaseOrphanedCellBindings(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verifyNoMoreInteractions(lifecycle);
    }
}
