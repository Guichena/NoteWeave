package com.noteweave.task;

import com.noteweave.common.ApiResponse;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    ResponseEntity<String> streamTaskEvents(@PathVariable String taskId) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Deprecation", "true")
                .header("Link", "</api/v2/tasks/%s/event-history>; rel=\"alternate\"".formatted(taskId))
                .body(taskService.streamEvents(taskId));
    }

    @GetMapping("/{taskId}/event-history")
    ApiResponse<List<TaskEventResponse>> getTaskEventHistory(@PathVariable String taskId) {
        return ApiResponse.success(taskService.listEvents(taskId));
    }
}
