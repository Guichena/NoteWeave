package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import com.noteweave.common.BusinessException;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal fail-closed permit boundary used before each distributed Research tool call. */
@RestController
@RequestMapping("/internal/research-agent")
public class ResearchAgentRateLimitInternalController {
    private static final Set<String> PERMIT_FIELDS = Set.of(
            "task_id", "worker_instance_id", "lease_epoch", "fencing_token", "tool_identity");
    private final ResearchAgentPermitService permitService;

    public ResearchAgentRateLimitInternalController(ResearchAgentPermitService permitService) {
        this.permitService = permitService;
    }

    @PostMapping("/permits")
    ApiResponse<ResearchAgentPermitService.PermitReceipt> requirePermit(
            @RequestBody Map<String, Object> request
    ) {
        if (request == null || !request.keySet().equals(PERMIT_FIELDS)) throw invalid();
        return ApiResponse.success(permitService.requirePermit(new ResearchAgentPermitService.PermitCommand(
                string(request, "task_id"),
                string(request, "worker_instance_id"),
                positiveInt(request, "lease_epoch"),
                positiveLong(request, "fencing_token"),
                string(request, "tool_identity"))));
    }

    private String string(Map<String, Object> request, String key) {
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
        return new BusinessException("RESEARCH_AGENT_PERMIT_INVALID", "Research agent permit request is invalid");
    }
}
