package com.noteweave.worker;

import com.noteweave.common.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/worker")
public class ArtifactWorkerControlController {

    private final ArtifactWorkerControlService artifactWorkerControlService;

    public ArtifactWorkerControlController(ArtifactWorkerControlService artifactWorkerControlService) {
        this.artifactWorkerControlService = artifactWorkerControlService;
    }

    @PostMapping("/artifact-tasks/{taskId}/resume")
    ApiResponse<ArtifactWorkerExecutionResponse> resumeArtifactTask(
            @PathVariable String taskId,
            @RequestBody(required = false) ArtifactWorkerResumeRequest request
    ) {
        return ApiResponse.success(artifactWorkerControlService.resumeTask(taskId, request));
    }

    @PostMapping("/artifact-callbacks/acquisition/ack")
    ApiResponse<ArtifactAcquisitionAckResponse> acknowledgeAcquisition(
            @RequestBody ArtifactAcquisitionAckRequest request
    ) {
        return ApiResponse.success(artifactWorkerControlService.acknowledgeAcquisition(request));
    }
}
