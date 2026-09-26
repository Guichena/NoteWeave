package com.noteweave.artifact;

import com.noteweave.common.ApiResponse;
import com.noteweave.security.CurrentUserProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Read and cancellation surface for the parent request; creation waits for material dispatch. */
@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/video-learning-bundles")
public class VideoLearningRequestController {
    private final VideoLearningRequestRepository requests;
    private final VideoLearningRequestService service;
    private final WorkspaceAccessGuard access;
    private final CurrentUserProvider users;

    public VideoLearningRequestController(VideoLearningRequestRepository requests,
                                          VideoLearningRequestService service,
                                          WorkspaceAccessGuard access,
                                          CurrentUserProvider users) {
        this.requests = requests;
        this.service = service;
        this.access = access;
        this.users = users;
    }

    @PostMapping
    ApiResponse<VideoLearningRequestRepository.ParentView> create(
            @PathVariable String workspaceId,
            @RequestBody CreateVideoLearningBundleRequest request) {
        return ApiResponse.success(service.create(workspaceId, request));
    }

    @GetMapping
    ApiResponse<Overview> list(@PathVariable String workspaceId) {
        access.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_READ);
        return ApiResponse.success(new Overview(service.isEnabled(),
                requests.recentForActor(workspaceId, users.requireUserId())));
    }

    @GetMapping("/{requestId}")
    ApiResponse<VideoLearningRequestRepository.ParentView> get(
            @PathVariable String workspaceId, @PathVariable String requestId) {
        access.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_READ);
        return ApiResponse.success(requests.view(workspaceId, requestId));
    }

    @PostMapping("/{requestId}/cancel")
    ApiResponse<VideoLearningRequestRepository.ParentView> cancel(
            @PathVariable String workspaceId, @PathVariable String requestId) {
        access.requirePermission(workspaceId, WorkspacePermission.EXECUTION_OPERATE);
        return ApiResponse.success(requests.requestCancellation(
                workspaceId, requestId, users.requireUserId()));
    }

    public record Overview(boolean enabled,
                           List<VideoLearningRequestRepository.ParentView> requests) {}
}
