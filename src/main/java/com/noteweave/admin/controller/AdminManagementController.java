package com.noteweave.admin.controller;

import com.noteweave.admin.dto.AdminSpaceDetailResponse;
import com.noteweave.admin.dto.AdminSpaceQuery;
import com.noteweave.admin.dto.AdminSpaceResponse;
import com.noteweave.admin.dto.AdminUserQuery;
import com.noteweave.admin.dto.AdminUserResponse;
import com.noteweave.admin.dto.MarkTaskFailedRequest;
import com.noteweave.admin.service.AdminSpaceService;
import com.noteweave.admin.service.AdminTaskService;
import com.noteweave.admin.service.AdminUserService;
import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.api.PageResponse;
import com.noteweave.common.security.CurrentUser;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.permission.service.ResourceAccessService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminManagementController {

    private final CurrentUserProvider currentUserProvider;
    private final ResourceAccessService resourceAccessService;
    private final AdminUserService adminUserService;
    private final AdminSpaceService adminSpaceService;
    private final TaskService taskService;
    private final AdminTaskService adminTaskService;

    @GetMapping("/users")
    public ApiResponse<PageResponse<AdminUserResponse>> searchUsers(@Valid @ModelAttribute AdminUserQuery query) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        return ApiResponse.success(adminUserService.searchUsers(query));
    }

    @PostMapping("/users/{userId}/disable")
    public ApiResponse<AdminUserResponse> disableUser(@PathVariable Long userId) {
        Long operatorId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(operatorId);
        return ApiResponse.success(adminUserService.disableUser(operatorId, userId));
    }

    @PostMapping("/users/{userId}/enable")
    public ApiResponse<AdminUserResponse> enableUser(@PathVariable Long userId) {
        Long operatorId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(operatorId);
        return ApiResponse.success(adminUserService.enableUser(operatorId, userId));
    }

    @GetMapping("/spaces")
    public ApiResponse<PageResponse<AdminSpaceResponse>> searchSpaces(@Valid @ModelAttribute AdminSpaceQuery query) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        return ApiResponse.success(adminSpaceService.searchSpaces(query));
    }

    @GetMapping("/spaces/{spaceId}")
    public ApiResponse<AdminSpaceDetailResponse> getSpace(@PathVariable Long spaceId) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        return ApiResponse.success(adminSpaceService.getSpace(userId, spaceId));
    }

    @PostMapping("/spaces/{spaceId}/archive")
    public ApiResponse<Void> archiveSpace(@PathVariable Long spaceId) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        adminSpaceService.archiveSpace(userId, spaceId);
        return ApiResponse.success(null);
    }

    @GetMapping("/tasks")
    public ApiResponse<PageResponse<TaskResponse>> searchTasks(@Valid @ModelAttribute TaskQuery query) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        resourceAccessService.requireAdmin(currentUser.userId());
        return ApiResponse.success(taskService.listTasks(currentUser, query));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<TaskResponse> getTask(@PathVariable Long taskId) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        resourceAccessService.requireAdmin(currentUser.userId());
        return ApiResponse.success(taskService.getTask(currentUser, taskId));
    }

    @GetMapping("/tasks/{taskId}/events")
    public ApiResponse<PageResponse<TaskEventResponse>> getTaskEvents(
            @PathVariable Long taskId,
            @Valid @ModelAttribute TaskEventQuery query
    ) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        resourceAccessService.requireAdmin(currentUser.userId());
        return ApiResponse.success(taskService.getTaskEvents(currentUser, taskId, query));
    }

    @PostMapping("/tasks/{taskId}/retry")
    public ApiResponse<TaskResponse> retryTask(@PathVariable Long taskId) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        resourceAccessService.requireAdmin(currentUser.userId());
        return ApiResponse.success(adminTaskService.retry(currentUser, taskId));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<TaskResponse> cancelTask(@PathVariable Long taskId) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        resourceAccessService.requireAdmin(currentUser.userId());
        return ApiResponse.success(adminTaskService.cancel(currentUser, taskId));
    }

    @PostMapping("/tasks/{taskId}/mark-failed")
    public ApiResponse<TaskResponse> markFailed(
            @PathVariable Long taskId,
            @Valid @RequestBody MarkTaskFailedRequest request
    ) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        resourceAccessService.requireAdmin(currentUser.userId());
        return ApiResponse.success(adminTaskService.markFailed(currentUser, taskId, request));
    }
}
