package com.noteweave.artifact;

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
 * ART-02：产物文件补偿队列的受控运维入口。
 *
 * <p>自动补偿失败到上限后版本停在 DEGRADED 终态，不再参与自动补偿；确认原因后
 * 由运维重驱放回队列。端点位于 {@code /internal/**}，由 InternalServiceAuthFilter 保护。</p>
 */
@RestController
@RequestMapping("/internal/artifacts/file-reconcile")
public class ArtifactFileReconcileInternalController {

    private final ArtifactExportService artifactExportService;

    public ArtifactFileReconcileInternalController(ArtifactExportService artifactExportService) {
        this.artifactExportService = artifactExportService;
    }

    @GetMapping("/metrics")
    ApiResponse<Map<String, Long>> metrics() {
        return ApiResponse.success(artifactExportService.fileReconcileMetrics());
    }

    @PostMapping("/versions/{versionId}/retry")
    ApiResponse<String> retry(
            @PathVariable String versionId,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "reason", required = false) String reason) {
        requireIdentity(actor, reason);
        if (!artifactExportService.retryFileReconcile(versionId)) {
            throw new BusinessException("ARTIFACT_FILE_RECONCILE_RETRY_REJECTED",
                    "仅处于 DEGRADED 的产物版本可以重驱");
        }
        return ApiResponse.success("RETRIED");
    }

    private void requireIdentity(String actor, String reason) {
        if (actor == null || actor.isBlank() || reason == null || reason.isBlank()) {
            throw new BusinessException("ARTIFACT_FILE_RECONCILE_IDENTITY_REQUIRED",
                    "人工重驱必须携带 actor 与 reason，保留可信恢复身份");
        }
    }
}
