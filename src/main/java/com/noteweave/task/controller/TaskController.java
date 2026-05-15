package com.noteweave.task.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.api.PageResponse;
import com.noteweave.common.security.CurrentUser;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.task.dto.TaskEventQuery;
import com.noteweave.task.dto.TaskEventResponse;
import com.noteweave.task.dto.TaskQuery;
import com.noteweave.task.dto.TaskResponse;
import com.noteweave.task.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public ApiResponse<PageResponse<TaskResponse>> listTasks(@Valid @ModelAttribute TaskQuery query) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        return ApiResponse.success(taskService.listTasks(currentUser, query));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskResponse> getTask(@PathVariable Long taskId) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        return ApiResponse.success(taskService.getTask(currentUser, taskId));
    }

    @GetMapping("/{taskId}/events")
    public ApiResponse<PageResponse<TaskEventResponse>> getTaskEvents(
            @PathVariable Long taskId,
            @Valid @ModelAttribute TaskEventQuery query
    ) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        return ApiResponse.success(taskService.getTaskEvents(currentUser, taskId, query));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<Void> cancelTask(@PathVariable Long taskId) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        taskService.cancelTask(currentUser, taskId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<TaskResponse> retryTask(@PathVariable Long taskId) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        return ApiResponse.success(taskService.retryTask(currentUser, taskId));
    }
}
