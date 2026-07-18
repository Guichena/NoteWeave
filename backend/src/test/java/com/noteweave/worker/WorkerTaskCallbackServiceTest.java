package com.noteweave.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.noteweave.artifact.ArtifactExportService;
import com.noteweave.artifact.ArtifactJobService;
import com.noteweave.research.ResearchRunService;
import com.noteweave.task.TaskResponse;
import com.noteweave.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkerTaskCallbackServiceTest {

    @Test
    void cancelledTaskShouldWinBeforeDuplicateHeartbeatLookup() {
        TaskService taskService = mock(TaskService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        WorkerTaskCallbackService service = new WorkerTaskCallbackService(
                taskService,
                mock(ArtifactJobService.class),
                mock(ArtifactExportService.class),
                mock(ResearchRunService.class),
                jdbcTemplate
        );
        when(taskService.getTaskRef("task-1")).thenReturn(new TaskService.TaskRef(
                "task-1", "workspace-1", "RESEARCH_RUN", "CANCELLED", "RESEARCH_RUN", "run-1"
        ));
        when(taskService.getTask("task-1")).thenReturn(new TaskResponse(
                "task-1", "RESEARCH_RUN", "CANCELLED", "", "", "", "", "RESEARCH_RUN", "run-1", null
        ));

        WorkerAckResponse response = service.heartbeat(
                "task-1",
                new WorkerHeartbeatRequest("research", "worker-1", "RESEARCH_LOOP", "2026-07-12T00:00:00Z"),
                "already-seen-key"
        );

        assertThat(response.status()).isEqualTo("CANCELLED");
        verifyNoInteractions(jdbcTemplate);
    }
}
