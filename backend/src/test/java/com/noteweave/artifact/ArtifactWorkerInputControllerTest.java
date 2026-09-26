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
                mock(ArtifactVideoMaterialService.class),
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
                mock(ArtifactVideoMaterialService.class),
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
                mock(ArtifactVideoMaterialService.class),
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
                jobService, mock(ArtifactVideoMaterialService.class), dispatcher, "production", false);

        assertThatThrownBy(() -> controller.getSourceWindows(
                "task-1", "source-1", "snapshot-1", "", 16, 65536, "stale-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not own the active outbox delivery");
        org.mockito.Mockito.verifyNoInteractions(jobService);
    }

    @Test
    void videoMaterialReadAndWriteRequireActiveDelivery() {
        ArtifactVideoMaterialService materials = mock(ArtifactVideoMaterialService.class);
        DurableOutboxDispatcher dispatcher = mock(DurableOutboxDispatcher.class);
        ArtifactWorkerInputController controller = new ArtifactWorkerInputController(
                mock(ArtifactJobService.class), materials, dispatcher, "production", false);
        ArtifactVideoMaterialService.Submission submission =
                new ArtifactVideoMaterialService.Submission(java.util.Map.of(), "digest");

        assertThatThrownBy(() -> controller.submitVideoMaterial("task-1", submission, "stale-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not own the active outbox delivery");
        assertThatThrownBy(() -> controller.getVideoMaterial("task-1", "stale-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not own the active outbox delivery");
        assertThatThrownBy(() -> controller.getVideoMaterialFile("task-1", "frame-1", "stale-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not own the active outbox delivery");
        assertThatThrownBy(() -> controller.getReferencedVideoMaterial(
                "task-1", "bundle-1", "stale-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not own the active outbox delivery");
        assertThatThrownBy(() -> controller.getReferencedVideoMaterialFile(
                "task-1", "bundle-1", "frame-1", "stale-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not own the active outbox delivery");
        org.mockito.Mockito.verifyNoInteractions(materials);

        when(dispatcher.renewTaskMessage("noteweave.artifact.job", "task-1", "delivery-1",
                OutboxDispatchPolicy.ARTIFACT_LEASE_DURATION)).thenReturn(true);
        controller.submitVideoMaterial("task-1", submission, "delivery-1");
        controller.getVideoMaterial("task-1", "delivery-1");
        when(materials.readFile("task-1", "frame-1")).thenReturn(
                new ArtifactVideoMaterialService.MaterialBytes("image/png", new byte[] {1, 2}));
        controller.getVideoMaterialFile("task-1", "frame-1", "delivery-1");
        when(materials.readReferencedFile("task-1", "bundle-1", "frame-1")).thenReturn(
                new ArtifactVideoMaterialService.MaterialBytes("image/png", new byte[] {1, 2}));
        controller.getReferencedVideoMaterial("task-1", "bundle-1", "delivery-1");
        controller.getReferencedVideoMaterialFile("task-1", "bundle-1", "frame-1", "delivery-1");
        verify(materials).submit("task-1", submission);
        verify(materials).read("task-1");
        verify(materials).readFile("task-1", "frame-1");
        verify(materials).readReferenced("task-1", "bundle-1");
        verify(materials).readReferencedFile("task-1", "bundle-1", "frame-1");
    }
}
