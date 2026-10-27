package com.noteweave.infra;

import com.noteweave.common.ApiResponse;
import com.noteweave.common.BusinessException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/outbox/task")
public class TaskOutboxController {

    /** actor 只允许稳定标识字符，防止把任意输入写进审计与日志。 */
    private static final Pattern ACTOR_PATTERN = Pattern.compile("[A-Za-z0-9:._-]{1,128}");

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

    /**
     * 人工重驱死信（FB-01）：调用方必须携带 actor 与 reason，审计记录可信调用身份，
     * 不再一律 INTERNAL。端点本身由内部服务鉴权保护；actor 是该内部调用者的可信自述标识。
     */
    @PostMapping("/dead-letters/{outboxId}/redrive")
    ApiResponse<String> redrive(
            @PathVariable String outboxId,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "reason", required = false) String reason) {
        String normalizedActor = actor == null ? "" : actor.trim();
        if (normalizedActor.isEmpty()) {
            throw new BusinessException("TASK_OUTBOX_REDRIVE_ACTOR_REQUIRED",
                    "人工重驱必须携带 actor，审计需要可信调用身份");
        }
        if (!ACTOR_PATTERN.matcher(normalizedActor).matches()) {
            throw new BusinessException("TASK_OUTBOX_REDRIVE_ACTOR_INVALID",
                    "actor 只允许字母、数字与 :._- 字符，长度 1-128");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("TASK_OUTBOX_REDRIVE_REASON_REQUIRED",
                    "人工重驱必须携带 reason，说明为什么重驱这条死信");
        }
        dispatcherService.redrive(outboxId, normalizedActor, reason.trim());
        return ApiResponse.success("REDRIVEN");
    }
}
