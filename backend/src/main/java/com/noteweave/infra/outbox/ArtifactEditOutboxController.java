package com.noteweave.infra.outbox;

import com.noteweave.common.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C4 编辑事件投递的运维端点（FB-03）：指标、死信查询与带身份的人工重驱。
 * 端点由内部服务鉴权保护；重驱必须携带 actor 与 reason，恢复身份写入事件行。
 */
@RestController
@RequestMapping("/internal/outbox/artifact-edit")
public class ArtifactEditOutboxController {

    private final ArtifactEditOutboxDispatcher dispatcher;

    public ArtifactEditOutboxController(ArtifactEditOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @GetMapping("/metrics")
    ApiResponse<Map<String, Long>> metrics() {
        return ApiResponse.success(dispatcher.metrics());
    }

    @GetMapping("/dead-letters")
    ApiResponse<List<ArtifactEditOutboxDispatcher.DeadLetterView>> deadLetters(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(dispatcher.listDeadLetters(limit));
    }

    @PostMapping("/dead-letters/{eventId}/redrive")
    ApiResponse<ArtifactEditOutboxDispatcher.RedriveOutcome> redrive(
            @PathVariable String eventId,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "reason", required = false) String reason) {
        return ApiResponse.success(dispatcher.redrive(eventId,
                actor == null ? "" : actor.trim(), reason == null ? "" : reason.trim()));
    }
}
