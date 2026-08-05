package com.noteweave.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskControllerTest {

    @Test
    void streamShouldEmitAuthoritativeTerminalSnapshotWhenCursorMissesTerminalEvent() throws Exception {
        TaskService taskService = mock(TaskService.class);
        when(taskService.getTask("task-1")).thenReturn(runningTask());
        when(taskService.listEventsForAuthorizedStream("task-1", "")).thenReturn(List.of());
        when(taskService.currentStatusForAuthorizedStream("task-1")).thenReturn("COMPLETED");
        Executor directExecutor = Runnable::run;
        TaskController controller = new TaskController(taskService, directExecutor, new ObjectMapper());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult pending = mockMvc.perform(get("/api/v2/tasks/task-1/events")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:task.completed")))
                .andExpect(content().string(containsString("\"snapshot\":true")));
    }

    private TaskResponse runningTask() {
        return new TaskResponse(
                "task-1",
                "SOURCE_PARSE",
                "RUNNING",
                "PARSING",
                "parsing",
                "",
                "",
                "SOURCE",
                "source-1",
                null
        );
    }
}
