package com.noteweave.worker;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
            @RequestHeader(value = "X-NoteWeave-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WorkerHeartbeatRequest request
    ) {
        return ApiResponse.success(workerTaskCallbackService.heartbeat(taskId, request, idempotencyKey));
    }

    @PostMapping("/{taskId}/progress")
    ApiResponse<WorkerAckResponse> progress(
            @PathVariable String taskId,
            @RequestHeader(value = "X-NoteWeave-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WorkerProgressRequest request
    ) {
        return ApiResponse.success(workerTaskCallbackService.progress(taskId, request, idempotencyKey));
    }

    @PostMapping("/{taskId}/complete")
    ApiResponse<WorkerAckResponse> complete(
            @PathVariable String taskId,
            @RequestHeader(value = "X-NoteWeave-Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-NoteWeave-Callback-Event-Id", required = false) String callbackEventId,
            @RequestHeader(value = "X-NoteWeave-Attempt-No", required = false) Integer attemptNo,
            @RequestHeader(value = "X-NoteWeave-Fencing-Token", required = false) Long fencingToken,
            @Valid @RequestBody WorkerCompleteRequest request
    ) {
        return ApiResponse.success(workerTaskCallbackService.complete(
                taskId, request, idempotencyKey, callbackEventId, attemptNo, fencingToken));
    }

    @PostMapping("/{taskId}/fail")
    ApiResponse<WorkerAckResponse> fail(
            @PathVariable String taskId,
            @RequestHeader(value = "X-NoteWeave-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WorkerFailRequest request
    ) {
        return ApiResponse.success(workerTaskCallbackService.fail(taskId, request, idempotencyKey));
    }
}
