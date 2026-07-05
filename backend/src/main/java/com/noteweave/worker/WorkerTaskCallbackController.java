package com.noteweave.worker;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/worker/tasks")
public class WorkerTaskCallbackController {

    private final WorkerTaskCallbackService workerTaskCallbackService;

    public WorkerTaskCallbackController(WorkerTaskCallbackService workerTaskCallbackService) {
        this.workerTaskCallbackService = workerTaskCallbackService;
    }

    @PostMapping("/{taskId}/heartbeat")
    ApiResponse<WorkerAckResponse> heartbeat(
            @PathVariable String taskId,
            @Valid @RequestBody WorkerHeartbeatRequest request
    ) {
        return ApiResponse.success(workerTaskCallbackService.heartbeat(taskId, request));
    }

    @PostMapping("/{taskId}/progress")
    ApiResponse<WorkerAckResponse> progress(
            @PathVariable String taskId,
            @Valid @RequestBody WorkerProgressRequest request
    ) {
        return ApiResponse.success(workerTaskCallbackService.progress(taskId, request));
    }

    @PostMapping("/{taskId}/complete")
    ApiResponse<WorkerAckResponse> complete(
            @PathVariable String taskId,
            @Valid @RequestBody WorkerCompleteRequest request
    ) {
        return ApiResponse.success(workerTaskCallbackService.complete(taskId, request));
    }

    @PostMapping("/{taskId}/fail")
    ApiResponse<WorkerAckResponse> fail(
            @PathVariable String taskId,
            @Valid @RequestBody WorkerFailRequest request
    ) {
        return ApiResponse.success(workerTaskCallbackService.fail(taskId, request));
    }
}
