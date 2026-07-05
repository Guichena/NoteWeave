package com.noteweave.artifact;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/artifact-jobs")
public class ArtifactJobController {

    private final ArtifactJobService artifactJobService;

    public ArtifactJobController(ArtifactJobService artifactJobService) {
        this.artifactJobService = artifactJobService;
    }

    @PostMapping
    ApiResponse<ArtifactJobResponse> createArtifactJob(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateArtifactJobRequest request
    ) {
        return ApiResponse.success(artifactJobService.createJob(workspaceId, request));
    }
}
