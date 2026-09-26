package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResearchAgentRunnableWorkServiceTest {

    @Test
    void disabledFlagPreservesLegacyActiveBarrier() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResearchAgentTaskCoordinatorService coordinator = mock(ResearchAgentTaskCoordinatorService.class);
        ResearchAgentRunnableWorkService service = new ResearchAgentRunnableWorkService(
                jdbc, new ObjectMapper(), coordinator, new ResearchAgentCompletionCanonicalizer(),
                mock(ResearchAgentFeatureFlagService.class), false);

        ResearchAgentRunnableWorkService.RefillReceipt receipt = service.refill(snapshot(2));

        assertThat(receipt.outcome()).isEqualTo("LEGACY_ACTIVE_NOOP");
        verifyNoInteractions(jdbc, coordinator);
    }

    @Test
    void enabledRefillCreatesOnlyMissingRunnableWorkThroughCoordinator() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResearchAgentTaskCoordinatorService coordinator = mock(ResearchAgentTaskCoordinatorService.class);
        ResearchAgentFeatureFlagService featureFlags = mock(ResearchAgentFeatureFlagService.class);
        when(featureFlags.enabledForRun("run-1", ResearchAgentFeatureFlagService.RUNNABLE_WORK)).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(2, 0, 0);
        ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskization =
                new ResearchAgentTaskCoordinatorService.CoordinatorReceipt(2, 0, 2, 2);
        when(coordinator.planAndEnqueueForWave("run-1", 1)).thenReturn(taskization);
        ResearchAgentRunnableWorkService service = new ResearchAgentRunnableWorkService(
                jdbc, new ObjectMapper(), coordinator, new ResearchAgentCompletionCanonicalizer(),
                featureFlags, true);

        ResearchAgentRunnableWorkService.RefillReceipt receipt = service.refill(snapshot(2));

        assertThat(receipt.outcome()).isEqualTo("ACTIVE_REFILL_TASKIZED");
        assertThat(receipt.runnableCellCount()).isEqualTo(2);
        assertThat(receipt.blockedCellCount()).isZero();
        verify(coordinator).planAndEnqueueForWave("run-1", 1);
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    private ResearchAgentCoordinatorSnapshotService.Snapshot snapshot(long activeTasks) {
        return new ResearchAgentCoordinatorSnapshotService.Snapshot(
                "run-1", 0, 1, 2, activeTasks, 0, 0, 2, 2);
    }
}
