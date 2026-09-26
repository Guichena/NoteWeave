package com.noteweave.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import com.noteweave.task.TaskService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArtifactWorkerControlServiceTest {
    private final ArtifactWorkerControlClient client = mock(ArtifactWorkerControlClient.class);
    private final WorkerTaskCallbackService callbacks = mock(WorkerTaskCallbackService.class);
    private final DurableOutboxDispatcher outbox = mock(DurableOutboxDispatcher.class);
    private final TaskService tasks = mock(TaskService.class);
    private final ArtifactWorkerControlService service =
            new ArtifactWorkerControlService(client, callbacks, outbox, tasks);

    @Test
    void acknowledgedProviderResumesWithTheCurrentHostDeliveryToken() {
        ArtifactAcquisitionAckRequest request = request();
        when(client.acknowledgeAcquisition(request)).thenReturn(acknowledged());
        when(tasks.getTaskRef("task-1")).thenReturn(task("WAITING"));
        when(outbox.activeTaskDeliveryToken("noteweave.artifact.job", "task-1"))
                .thenReturn("delivery-1");
        ArtifactWorkerExecutionResponse resumed = new ArtifactWorkerExecutionResponse(
                "task-1", "COMPLETED", 5, "Course Notes");
        when(client.resumeTask("task-1", new ArtifactWorkerResumeRequest("request-1", "delivery-1")))
                .thenReturn(resumed);

        ArtifactAcquisitionAckResponse response = service.acknowledgeAcquisition(request);

        assertThat(response.resumedTasks()).containsExactly(resumed);
        verify(client).resumeTask("task-1", new ArtifactWorkerResumeRequest("request-1", "delivery-1"));
    }

    @Test
    void acknowledgedProviderReplayDoesNotResumeAnAlreadyTerminalTask() {
        when(client.acknowledgeAcquisition(request())).thenReturn(acknowledged());
        when(tasks.getTaskRef("task-1")).thenReturn(task("COMPLETED"));

        assertThat(service.acknowledgeAcquisition(request()).resumedTasks()).isEmpty();
        verifyNoInteractions(outbox);
    }

    @Test
    void missingActiveDeliveryKeepsProviderAckRetryable() {
        when(client.acknowledgeAcquisition(request())).thenReturn(acknowledged());
        when(tasks.getTaskRef("task-1")).thenReturn(task("WAITING"));

        assertThatThrownBy(() -> service.acknowledgeAcquisition(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active artifact delivery");
    }

    private ArtifactAcquisitionAckRequest request() {
        return new ArtifactAcquisitionAckRequest("provider-token", "ACKNOWLEDGED", "", "", "", Map.of());
    }

    private TaskService.TaskRef task(String status) {
        return new TaskService.TaskRef("task-1", "workspace-1", "ARTIFACT_JOB",
                status, "ARTIFACT_JOB", "job-1");
    }

    private ArtifactAcquisitionAckResponse acknowledged() {
        ArtifactAcquisitionOperationResponse operation = new ArtifactAcquisitionOperationResponse(
                "request-1", "task-1", "EXTRACT_TRANSCRIPT", "builtin-bilibili-mcp",
                "builtin-bilibili-mcp", "get_bilibili_subtitle", "", "", "", "",
                "AVAILABLE", "HEALTHY", "SUCCEEDED", "ACKNOWLEDGED", 1, List.of());
        ArtifactAcquisitionReceiptResponse receipt = new ArtifactAcquisitionReceiptResponse(
                "receipt-1", "request-1", "task-1", "input-url-1", "EXTRACT_TRANSCRIPT",
                "", "", "SUCCEEDED", "ACKNOWLEDGED", "", "", "", "", "", "", 1);
        return new ArtifactAcquisitionAckResponse(operation, receipt, List.of());
    }
}
