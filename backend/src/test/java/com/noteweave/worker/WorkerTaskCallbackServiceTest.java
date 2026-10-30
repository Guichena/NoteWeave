package com.noteweave.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.artifact.ArtifactExportService;
import com.noteweave.artifact.ArtifactJobService;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import com.noteweave.infra.outbox.OutboxDispatchPolicy;
import com.noteweave.task.TaskResponse;
import com.noteweave.task.TaskService;
import com.noteweave.common.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkerTaskCallbackServiceTest {

    @Test
    void terminalCallbacksMustRequireAnIdempotencyKey() {
        WorkerTaskCallbackService service = new WorkerTaskCallbackService(
                mock(TaskService.class),
                mock(ArtifactJobService.class),
                mock(ArtifactExportService.class),
                mock(JdbcTemplate.class)
        );

        assertThatThrownBy(() -> service.complete(
                "task-1",
                new WorkerCompleteRequest("MARKDOWN", "title", java.util.Map.of(), "", java.util.List.of()),
                ""
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("WORKER_CALLBACK_IDEMPOTENCY_REQUIRED");
        assertThatThrownBy(() -> service.fail(
                "task-1",
                new WorkerFailRequest("FAILED", "ERROR", "failed", false),
                " "
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("WORKER_CALLBACK_IDEMPOTENCY_REQUIRED");
    }

    @Test
    void cancelledTaskShouldWinBeforeDuplicateHeartbeatLookup() {
        TaskService taskService = mock(TaskService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        WorkerTaskCallbackService service = new WorkerTaskCallbackService(
                taskService,
                mock(ArtifactJobService.class),
                mock(ArtifactExportService.class),
                jdbcTemplate
        );
        when(taskService.getTaskRef("task-1")).thenReturn(new TaskService.TaskRef(
                "task-1", "workspace-1", "ARTIFACT_JOB", "CANCELLED", "ARTIFACT_JOB", "job-1"
        ));
        when(taskService.getTask("task-1")).thenReturn(new TaskResponse(
                "task-1", "ARTIFACT_JOB", "CANCELLED", "", "", "", "", "ARTIFACT_JOB", "job-1", null
        ));

        WorkerAckResponse response = service.heartbeat(
                "task-1",
                new WorkerHeartbeatRequest("artifact", "worker-1", "GENERATE", "2026-07-12T00:00:00Z"),
                "already-seen-key"
        );

        assertThat(response.status()).isEqualTo("CANCELLED");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void callbackMustRejectTasksThatAreNotOwnedByAnExternalWorker() {
        TaskService taskService = mock(TaskService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        WorkerTaskCallbackService service = new WorkerTaskCallbackService(
                taskService,
                mock(ArtifactJobService.class),
                mock(ArtifactExportService.class),
                jdbcTemplate
        );
        when(taskService.getTaskRef("task-source")).thenReturn(new TaskService.TaskRef(
                "task-source", "workspace-1", "SOURCE_PARSE", "RUNNING", "SOURCE", "source-1"
        ));

        assertThatThrownBy(() -> service.fail(
                "task-source",
                new WorkerFailRequest("FAILED", "WORKER_ERROR", "must not mutate source task", false),
                "callback-1"
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("WORKER_CALLBACK_TASK_TYPE_INVALID");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void genericCallbackMustRejectResearchTasks() {
        TaskService taskService = mock(TaskService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        WorkerTaskCallbackService service = new WorkerTaskCallbackService(
                taskService,
                mock(ArtifactJobService.class),
                mock(ArtifactExportService.class),
                jdbcTemplate
        );
        when(taskService.getTaskRef("task-research")).thenReturn(new TaskService.TaskRef(
                "task-research", "workspace-1", "RESEARCH_RUN", "RUNNING", "RESEARCH_RUN", "run-1"
        ));

        assertThatThrownBy(() -> service.progress(
                "task-research",
                new WorkerProgressRequest(
                        "RESEARCH_LOOP", 50, "legacy callback", java.util.Map.of(), java.util.Map.of()),
                "callback-1"
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("WORKER_CALLBACK_TASK_TYPE_INVALID");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void heartbeatRenewsTheArtifactOutboxLeaseForVideoMaterial() {
        TaskService taskService = mock(TaskService.class);
        ArtifactJobService artifactJobService = mock(ArtifactJobService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DurableOutboxDispatcher outbox = mock(DurableOutboxDispatcher.class);
        WorkerTaskCallbackService service = new WorkerTaskCallbackService(
                taskService,
                artifactJobService,
                mock(ArtifactExportService.class),
                jdbcTemplate,
                outbox,
                new ObjectMapper()
        );
        when(taskService.getTaskRef("task-1")).thenReturn(materialTask("RUNNING"));
        when(outbox.renewTaskMessage(
                "noteweave.artifact.job",
                "task-1",
                "delivery-1",
                OutboxDispatchPolicy.ARTIFACT_LEASE_DURATION
        )).thenReturn(true);
        when(jdbcTemplate.queryForObject(
                contains("video_learning_request"),
                eq(Integer.class),
                eq("parent-1"),
                eq("task-1"),
                eq("workspace-1")
        )).thenReturn(1);

        assertThatThrownBy(() -> service.heartbeatFromDelivery(
                "task-1",
                new WorkerHeartbeatRequest("artifact", "worker-1", "EXECUTING", "2026-10-21T00:00:00Z"),
                "",
                "delivery-1"
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("WORKER_CALLBACK_TASK_TYPE_INVALID");

        verify(taskService, never()).recordHeartbeat(anyString(), anyString(), anyString(), any());
        verifyNoInteractions(artifactJobService);
    }

    @Test
    void videoMaterialHeartbeatRejectsAStaleDeliveryBeforeRecording() {
        TaskService taskService = mock(TaskService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DurableOutboxDispatcher outbox = mock(DurableOutboxDispatcher.class);
        WorkerTaskCallbackService service = new WorkerTaskCallbackService(
                taskService,
                mock(ArtifactJobService.class),
                mock(ArtifactExportService.class),
                jdbcTemplate,
                outbox,
                new ObjectMapper()
        );
        when(taskService.getTaskRef("task-1")).thenReturn(materialTask("RUNNING"));
        when(outbox.renewTaskMessage(anyString(), anyString(), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.heartbeatFromDelivery(
                "task-1",
                new WorkerHeartbeatRequest("artifact", "worker-1", "EXECUTING", "2026-10-21T00:00:00Z"),
                "",
                "delivery-1"
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("WORKER_CALLBACK_DELIVERY_STALE");

        verify(taskService, never()).recordHeartbeat(anyString(), anyString(), anyString(), any());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void videoMaterialHeartbeatRejectsAParentTheTaskDoesNotOwn() {
        TaskService taskService = mock(TaskService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        WorkerTaskCallbackService service = new WorkerTaskCallbackService(
                taskService,
                mock(ArtifactJobService.class),
                mock(ArtifactExportService.class),
                jdbcTemplate
        );
        when(taskService.getTaskRef("task-1")).thenReturn(materialTask("RUNNING"));
        when(jdbcTemplate.queryForObject(contains("video_learning_request"), eq(Integer.class), any(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.heartbeat(
                "task-1",
                new WorkerHeartbeatRequest("artifact", "worker-1", "EXECUTING", "2026-10-21T00:00:00Z"),
                ""
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("WORKER_CALLBACK_TASK_TYPE_INVALID");

        verify(taskService, never()).recordHeartbeat(anyString(), anyString(), anyString(), any());
    }

    @Test
    void videoMaterialHeartbeatRejectsATaskThatIsNotBoundToItsParentRequest() {
        TaskService taskService = mock(TaskService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        WorkerTaskCallbackService service = new WorkerTaskCallbackService(
                taskService,
                mock(ArtifactJobService.class),
                mock(ArtifactExportService.class),
                jdbcTemplate
        );
        when(taskService.getTaskRef("task-1")).thenReturn(new TaskService.TaskRef(
                "task-1", "workspace-1", "VIDEO_MATERIAL", "RUNNING", "ARTIFACT_JOB", "job-1"
        ));

        assertThatThrownBy(() -> service.heartbeat(
                "task-1",
                new WorkerHeartbeatRequest("artifact", "worker-1", "EXECUTING", "2026-10-21T00:00:00Z"),
                ""
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("WORKER_CALLBACK_TASK_TYPE_INVALID");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void progressAndCompleteStillRejectVideoMaterialTasks() {
        TaskService taskService = mock(TaskService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        WorkerTaskCallbackService service = new WorkerTaskCallbackService(
                taskService,
                mock(ArtifactJobService.class),
                mock(ArtifactExportService.class),
                jdbcTemplate
        );
        when(taskService.getTaskRef("task-1")).thenReturn(materialTask("RUNNING"));

        assertThatThrownBy(() -> service.progress(
                "task-1",
                new WorkerProgressRequest("ACQUIRING", 10, "material", Map.of(), Map.of()),
                "callback-1"
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("WORKER_CALLBACK_TASK_TYPE_INVALID");
        assertThatThrownBy(() -> service.complete(
                "task-1",
                new WorkerCompleteRequest("MARKDOWN", "title", Map.of(), "", java.util.List.of()),
                "callback-1"
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("WORKER_CALLBACK_TASK_TYPE_INVALID");

        verifyNoInteractions(jdbcTemplate);
    }

    private static TaskService.TaskRef materialTask(String status) {
        return new TaskService.TaskRef(
                "task-1", "workspace-1", "VIDEO_MATERIAL", status,
                "VIDEO_LEARNING_REQUEST", "parent-1"
        );
    }
}
