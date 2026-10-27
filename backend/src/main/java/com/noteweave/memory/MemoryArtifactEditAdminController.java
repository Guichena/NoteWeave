package com.noteweave.memory;

import com.noteweave.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 产物编辑事件消费的运维端点（MEM-04）。
 *
 * <p>这个开关默认关闭：合法事件仍会持久 PENDING 回执后确认，只是不再推进。
 * 没有指标就看不出「关着的时候堆了多少」，所以单独暴露回执积压。
 * 端点由内部服务鉴权保护。</p>
 */
@RestController
@RequestMapping("/internal/memory/artifact-edit")
public class MemoryArtifactEditAdminController {

    private final MemoryArtifactEditService service;

    public MemoryArtifactEditAdminController(MemoryArtifactEditService service) {
        this.service = service;
    }

    @GetMapping("/receipts/metrics")
    ApiResponse<Map<String, Long>> receiptMetrics() {
        return ApiResponse.success(service.receiptBacklog());
    }
}
