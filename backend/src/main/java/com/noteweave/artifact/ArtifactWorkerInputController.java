package com.noteweave.artifact;

import com.noteweave.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/worker/artifact-tasks")
public class ArtifactWorkerInputController {

    private final ArtifactJobService artifactJobService;

    public ArtifactWorkerInputController(ArtifactJobService artifactJobService) {
        this.artifactJobService = artifactJobService;
    }

    @GetMapping("/{taskId}/input")
    ApiResponse<ArtifactWorkerInputResponse> getTaskInput(@PathVariable String taskId) {
        return ApiResponse.success(artifactJobService.getWorkerInput(taskId));
    }
}
