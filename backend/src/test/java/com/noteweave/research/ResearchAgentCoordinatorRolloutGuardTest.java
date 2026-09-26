package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
        var snapshot = new ResearchAgentCoordinatorSnapshotService.Snapshot(
                "run-1", 0, 1, 0, 0, 0, 0, 0, 2);
        when(snapshots.snapshot("run-1")).thenReturn(snapshot);
        when(guard.evaluate()).thenReturn(new ResearchAgentRolloutPolicy.Decision(
                ResearchAgentRolloutPolicy.Action.PAUSE_NEW_INITIAL_WAVES,
                List.of("DELIVERY_FAILURE_RATE_EXCEEDED"),
                Map.of("delivery_failure_rate", 0.25d)));
        var service = new ResearchAgentCoordinatorTickService(
                snapshots, coordinator, recovery, faults, finalization, guard, lifecycle,
                runnableWork, roleWorkflow, completionGate, honestReports,
                globalConflictService, conflictRepairService);

        var receipt = service.tick("run-1", "coordinator-1");

        assertThat(receipt.outcome()).isEqualTo("ROLLOUT_PAUSED");
        verifyNoInteractions(coordinator, recovery, faults, finalization, lifecycle, runnableWork, roleWorkflow);
    }
}
