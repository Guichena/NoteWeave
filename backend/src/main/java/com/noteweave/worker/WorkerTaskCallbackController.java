package com.noteweave.worker;

import com.noteweave.common.ApiResponse;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
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
    private final WorkerTaskCallbackAuthenticator callbackAuthenticator;

    public WorkerTaskCallbackController(
            WorkerTaskCallbackService workerTaskCallbackService,
            WorkerTaskCallbackAuthenticator callbackAuthenticator
    ) {
        this.workerTaskCallbackService = workerTaskCallbackService;
        this.callbackAuthenticator = callbackAuthenticator;
    }

    @PostMapping("/{taskId}/heartbeat")
    ApiResponse<WorkerAckResponse> heartbeat(
            @PathVariable String taskId,
            @RequestHeader(value = WorkerTaskCallbackAuthenticator.HEADER_NAME, required = false) String callbackToken,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER, required = false) String deliveryToken,
            @RequestHeader(value = "X-NoteWeave-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WorkerHeartbeatRequest request
    ) {
        callbackAuthenticator.authenticate(taskId, callbackToken);
        return ApiResponse.success(workerTaskCallbackService.heartbeatFromDelivery(
                taskId, request, idempotencyKey, deliveryToken
        ));
    }

    @PostMapping("/{taskId}/progress")
    ApiResponse<WorkerAckResponse> progress(
            @PathVariable String taskId,
            @RequestHeader(value = WorkerTaskCallbackAuthenticator.HEADER_NAME, required = false) String callbackToken,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER, required = false) String deliveryToken,
            @RequestHeader(value = "X-NoteWeave-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WorkerProgressRequest request
    ) {
        callbackAuthenticator.authenticate(taskId, callbackToken);
        return ApiResponse.success(workerTaskCallbackService.progressFromDelivery(
                taskId, request, idempotencyKey, deliveryToken
        ));
    }

    @PostMapping("/{taskId}/complete")
    ApiResponse<WorkerAckResponse> complete(
            @PathVariable String taskId,
            @RequestHeader(value = WorkerTaskCallbackAuthenticator.HEADER_NAME, required = false) String callbackToken,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER, required = false) String deliveryToken,
            @RequestHeader(value = "X-NoteWeave-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WorkerCompleteRequest request
    ) {
        callbackAuthenticator.authenticate(taskId, callbackToken);
        return ApiResponse.success(workerTaskCallbackService.completeFromDelivery(
                taskId, request, idempotencyKey, deliveryToken
        ));
    }

    @PostMapping("/{taskId}/fail")
    ApiResponse<WorkerAckResponse> fail(
            @PathVariable String taskId,
            @RequestHeader(value = WorkerTaskCallbackAuthenticator.HEADER_NAME, required = false) String callbackToken,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER, required = false) String deliveryToken,
            @RequestHeader(value = "X-NoteWeave-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WorkerFailRequest request
    ) {
        callbackAuthenticator.authenticate(taskId, callbackToken);
        return ApiResponse.success(workerTaskCallbackService.failFromDelivery(
                taskId, request, idempotencyKey, deliveryToken
        ));
    }
}
