package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.common.BusinessException;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import com.noteweave.infra.outbox.OutboxDispatchPolicy;
import org.junit.jupiter.api.Test;

class ArtifactWorkerInputControllerTest {

    @Test
    void productionMustRejectUnfencedInputEvenWhenDiagnosticFlagIsEnabled() {
        ArtifactWorkerInputController controller = new ArtifactWorkerInputController(
                mock(ArtifactJobService.class),
                mock(DurableOutboxDispatcher.class),
                "production",
                true
        );

        assertThatThrownBy(() -> controller.getTaskInput("task-1", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("require an active outbox delivery token");
    }

    @Test
    void staleDeliveryMustBeRejectedBeforeFrozenInputIsRead() {
        ArtifactJobService jobService = mock(ArtifactJobService.class);
        DurableOutboxDispatcher dispatcher = mock(DurableOutboxDispatcher.class);
        ArtifactWorkerInputController controller = new ArtifactWorkerInputController(
                jobService,
                dispatcher,
                "development",
                false
        );
        when(dispatcher.renewTaskMessage(
                "noteweave.artifact.job",
                "task-1",
                "stale-token",
                OutboxDispatchPolicy.ARTIFACT_LEASE_DURATION
        )).thenReturn(false);

        assertThatThrownBy(() -> controller.getTaskInput("task-1", "stale-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not own the active outbox delivery");
    }

    @Test
    void activeDeliveryMustRenewLeaseBeforeFrozenInputIsRead() {
        ArtifactJobService jobService = mock(ArtifactJobService.class);
        DurableOutboxDispatcher dispatcher = mock(DurableOutboxDispatcher.class);
        ArtifactWorkerInputController controller = new ArtifactWorkerInputController(
                jobService,
                dispatcher,
                "development",
                false
        );
        when(dispatcher.renewTaskMessage(
                "noteweave.artifact.job",
                "task-1",
                "delivery-1",
                OutboxDispatchPolicy.ARTIFACT_LEASE_DURATION
        )).thenReturn(true);

        controller.getTaskInput("task-1", "delivery-1");

        verify(jobService).getWorkerInput("task-1");
    }
}
