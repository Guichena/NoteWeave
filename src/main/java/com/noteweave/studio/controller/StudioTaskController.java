package com.noteweave.studio.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUser;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.studio.dto.CreateStudioTaskRequest;
import com.noteweave.studio.dto.CreateStudioTaskResponse;
import com.noteweave.studio.service.StudioTaskService;
import com.noteweave.task.dto.TaskResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/studio/tasks")
@RequiredArgsConstructor
public class StudioTaskController {

    private final StudioTaskService studioTaskService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    public ApiResponse<CreateStudioTaskResponse> create(@Valid @RequestBody CreateStudioTaskRequest request) {
        return ApiResponse.success(studioTaskService.createTask(currentUserProvider.getCurrentUser().userId(), request));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskResponse> getTask(@PathVariable Long taskId) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        return ApiResponse.success(studioTaskService.getTask(currentUser, taskId));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long taskId) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        studioTaskService.cancel(currentUser, taskId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<TaskResponse> retry(@PathVariable Long taskId) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        return ApiResponse.success(studioTaskService.retry(currentUser, taskId));
    }
}
