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
                .hasMessageContaining("有效的 outbox 投递令牌");
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
                .hasMessageContaining("不属于当前有效的 outbox 投递");
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

    @Test
    void sourceWindowReadRequiresActiveDeliveryBeforeReadingMaterial() {
        ArtifactJobService jobService = mock(ArtifactJobService.class);
        DurableOutboxDispatcher dispatcher = mock(DurableOutboxDispatcher.class);
        ArtifactWorkerInputController controller = new ArtifactWorkerInputController(
                jobService, dispatcher, "production", false);

        assertThatThrownBy(() -> controller.getSourceWindows(
                "task-1", "source-1", "snapshot-1", "", 16, 65536, "stale-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前有效的 outbox 投递");
        org.mockito.Mockito.verifyNoInteractions(jobService);
    }

}
