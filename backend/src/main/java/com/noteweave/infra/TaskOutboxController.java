package com.noteweave.infra;

import com.noteweave.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/outbox/task")
public class TaskOutboxController {

    private final TaskOutboxDispatcherService dispatcherService;

    public TaskOutboxController(TaskOutboxDispatcherService dispatcherService) {
        this.dispatcherService = dispatcherService;
    }

    @GetMapping("/metrics")
    ApiResponse<TaskOutboxMetricsResponse> metrics() {
        return ApiResponse.success(dispatcherService.metrics());
    }

    @GetMapping("/dead-letters")
    ApiResponse<List<TaskOutboxDeadLetterResponse>> deadLetters(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(dispatcherService.listDeadLetters(limit));
    }

    @PostMapping("/dead-letters/{outboxId}/redrive")
    ApiResponse<String> redrive(@PathVariable String outboxId) {
        dispatcherService.redrive(outboxId);
        return ApiResponse.success("REDRIVEN");
    }
}
