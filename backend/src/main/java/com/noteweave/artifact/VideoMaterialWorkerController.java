package com.noteweave.artifact;

import com.noteweave.common.ApiResponse;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import com.noteweave.worker.WorkerTaskCallbackAuthenticator;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/worker/video-material-tasks")
public class VideoMaterialWorkerController {
    private final VideoMaterialTaskService tasks;
    private final WorkerTaskCallbackAuthenticator callbackAuth;

    public VideoMaterialWorkerController(VideoMaterialTaskService tasks,
                                         WorkerTaskCallbackAuthenticator callbackAuth) {
        this.tasks = tasks;
        this.callbackAuth = callbackAuth;
    }

    @GetMapping("/{taskId}/input")
    ApiResponse<VideoMaterialTaskInput> input(@PathVariable String taskId,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER,
                    defaultValue = "") String deliveryToken) {
        return ApiResponse.success(tasks.claimInput(taskId, deliveryToken));
    }

    @PostMapping("/{taskId}/bundle")
    ApiResponse<ArtifactVideoMaterialService.Receipt> bundle(@PathVariable String taskId,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER,
                    defaultValue = "") String deliveryToken,
            @RequestBody ArtifactVideoMaterialService.Submission submission) {
        return ApiResponse.success(tasks.submitBundle(taskId, deliveryToken, submission));
    }

    @PostMapping("/{taskId}/knowledge-plan")
    ApiResponse<ArtifactVideoMaterialService.KnowledgeReceipt> plan(@PathVariable String taskId,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER,
                    defaultValue = "") String deliveryToken,
            @RequestBody ArtifactVideoMaterialService.KnowledgeSubmission submission) {
        return ApiResponse.success(tasks.submitPlan(taskId, deliveryToken, submission));
    }

    @PostMapping("/{taskId}/complete")
    ApiResponse<Map<String, String>> complete(@PathVariable String taskId,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER,
                    defaultValue = "") String deliveryToken,
            @RequestHeader(value = "X-NoteWeave-Task-Callback-Token",
                    defaultValue = "") String callbackToken,
            @RequestBody Completion submission) {
        callbackAuth.authenticate(taskId, callbackToken);
        return ApiResponse.success(Map.of("status", tasks.complete(taskId, deliveryToken,
                submission.bundleId(), submission.planId())));
    }

    @PostMapping("/{taskId}/fail")
    ApiResponse<Map<String, String>> fail(@PathVariable String taskId,
            @RequestHeader(value = DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER,
                    defaultValue = "") String deliveryToken,
            @RequestHeader(value = "X-NoteWeave-Task-Callback-Token",
                    defaultValue = "") String callbackToken,
            @RequestBody Failure submission) {
        callbackAuth.authenticate(taskId, callbackToken);
        return ApiResponse.success(Map.of("status", tasks.fail(taskId, deliveryToken,
                submission.errorCode())));
    }

    public record Completion(String bundleId, String planId) {}
    public record Failure(String errorCode) {}
}
