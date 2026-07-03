package com.noteweave.workspace;

import com.noteweave.common.ApiResponse;
import com.noteweave.knowledge.WikiIngestService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WikiIngestService wikiIngestService;

    public WorkspaceController(WorkspaceService workspaceService, WikiIngestService wikiIngestService) {
        this.workspaceService = workspaceService;
        this.wikiIngestService = wikiIngestService;
    }

    @PostMapping
    ApiResponse<WorkspaceResponse> createWorkspace(@Valid @RequestBody CreateWorkspaceRequest request) {
        return ApiResponse.success(workspaceService.createWorkspace(request));
    }

    @GetMapping("/{workspaceId}/wiki-settings")
    ApiResponse<WorkspaceWikiSettingsResponse> getWikiSettings(@PathVariable String workspaceId) {
        return ApiResponse.success(workspaceService.getWikiSettings(workspaceId));
    }

    @PutMapping("/{workspaceId}/wiki-settings")
    ApiResponse<WorkspaceWikiSettingsResponse> updateWikiSettings(
            @PathVariable String workspaceId,
            @RequestBody UpdateWorkspaceWikiSettingsRequest request
    ) {
        boolean wasEnabled = workspaceService.isWikiEnabled(workspaceId);
        WorkspaceWikiSettingsResponse response = workspaceService.updateWikiSettings(workspaceId, request);
        if (!wasEnabled && request.wikiEnabled()) {
            wikiIngestService.enqueueAndRunWorkspaceIngestIfEnabled(workspaceId, "enable_backfill");
        }
        return ApiResponse.success(response);
    }
}
