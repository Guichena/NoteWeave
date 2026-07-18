package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import com.noteweave.common.BusinessException;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal-only archive boundary.  It rejects unknown fields instead of ignoring spoofed scope. */
@RestController
@RequestMapping("/internal/research-agent")
public class ResearchExternalSnapshotArchiveInternalController {
    private static final Set<String> FIELDS = Set.of(
            "task_id", "worker_instance_id", "lease_epoch", "fencing_token", "window_id", "source_id",
            "source_title", "source_url", "provider", "adapter", "content_text");
    private final ResearchExternalSnapshotArchiveService service;

    public ResearchExternalSnapshotArchiveInternalController(ResearchExternalSnapshotArchiveService service) {
        this.service = service;
    }

    @PostMapping("/external-snapshots")
    ApiResponse<ResearchExternalSnapshotArchiveService.ArchiveReceipt> archive(@RequestBody Map<String, Object> request) {
        if (request == null || !request.keySet().equals(FIELDS)) throw invalid();
        return ApiResponse.success(service.archive(new ResearchExternalSnapshotArchiveService.ArchiveCommand(
                text(request, "task_id"), text(request, "worker_instance_id"), positiveInt(request, "lease_epoch"),
                positiveLong(request, "fencing_token"), text(request, "window_id"), text(request, "source_id"),
                text(request, "source_title"), text(request, "source_url"), text(request, "provider"),
                text(request, "adapter"), text(request, "content_text"))));
    }

    private String text(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value instanceof String text && !text.isBlank()) return text;
        throw invalid();
    }

    private int positiveInt(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value instanceof Integer number && number > 0) return number;
        throw invalid();
    }

    private long positiveLong(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value instanceof Integer number && number > 0) return number.longValue();
        if (value instanceof Long number && number > 0) return number;
        throw invalid();
    }

    private BusinessException invalid() {
        return new BusinessException("RESEARCH_AGENT_EXTERNAL_ARCHIVE_INVALID", "External snapshot archive request is invalid");
    }
}
