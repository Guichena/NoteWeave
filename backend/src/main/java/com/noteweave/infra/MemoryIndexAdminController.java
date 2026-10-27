package com.noteweave.infra;

import com.noteweave.common.ApiResponse;
import com.noteweave.common.BusinessException;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Memory / Wiki 索引发件箱的最小受控运维入口（FB-08.6）：
 * 指标可见 + FAILED 行带身份重驱（代次校验在仓储侧拒绝被取代代次）。
 * 不开放无权限的状态重置：端点位于 /internal/**，由 InternalServiceAuthFilter 保护。
 */
@RestController
@RequestMapping("/internal/index-outbox")
public class MemoryIndexAdminController {

    private final com.noteweave.memory.MemoryIndexOutbox memoryIndexOutbox;
    private final com.noteweave.knowledge.WikiIndexOutbox wikiIndexOutbox;

    public MemoryIndexAdminController(
            com.noteweave.memory.MemoryIndexOutbox memoryIndexOutbox,
            com.noteweave.knowledge.WikiIndexOutbox wikiIndexOutbox) {
        this.memoryIndexOutbox = memoryIndexOutbox;
        this.wikiIndexOutbox = wikiIndexOutbox;
    }

    @GetMapping("/metrics")
    ApiResponse<Map<String, Long>> metrics() {
        Map<String, Long> merged = new java.util.LinkedHashMap<>(memoryIndexOutbox.metrics());
        wikiIndexOutbox.metrics().forEach(merged::putIfAbsent);
        return ApiResponse.success(merged);
    }

    @PostMapping("/memory/{outboxId}/redrive")
    ApiResponse<String> redriveMemory(
            @PathVariable String outboxId,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "reason", required = false) String reason) {
        requireIdentity(actor, reason);
        if (!memoryIndexOutbox.redriveFailed(outboxId, actor.trim(), reason.trim())) {
            throw new BusinessException("MEMORY_INDEX_REDRIVE_REJECTED",
                    "仅当前权威代次的 FAILED 行可重驱");
        }
        return ApiResponse.success("REDRIVEN");
    }

    @PostMapping("/wiki/{outboxId}/redrive")
    ApiResponse<String> redriveWiki(
            @PathVariable String outboxId,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "reason", required = false) String reason) {
        requireIdentity(actor, reason);
        if (!wikiIndexOutbox.redriveFailed(outboxId, actor.trim(), reason.trim())) {
            throw new BusinessException("WIKI_INDEX_REDRIVE_REJECTED",
                    "仅当前权威代次的 FAILED 行可重驱");
        }
        return ApiResponse.success("REDRIVEN");
    }

    private void requireIdentity(String actor, String reason) {
        if (actor == null || actor.isBlank() || reason == null || reason.isBlank()) {
            throw new BusinessException("INDEX_REDRIVE_IDENTITY_REQUIRED",
                    "人工重驱必须携带 actor 与 reason，保留可信恢复身份");
        }
    }
}
