package com.noteweave.task;

import com.noteweave.common.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/{taskId}")
    ApiResponse<TaskResponse> getTask(@PathVariable String taskId) {
        return ApiResponse.success(taskService.getTask(taskId));
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    String streamTaskEvents(@PathVariable String taskId) {
        return taskService.streamEvents(taskId);
    }
}
