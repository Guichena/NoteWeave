package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import com.noteweave.common.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal fail-closed boundary for lease-bound workspace source-window search. */
@RestController
@RequestMapping("/internal/research-agent")
public class ResearchWorkspaceWindowSearchInternalController {
    private static final Set<String> FIELDS = Set.of(
            "task_id", "worker_instance_id", "lease_epoch", "fencing_token", "queries", "limit");
    private final ResearchWorkspaceWindowSearchService service;

    public ResearchWorkspaceWindowSearchInternalController(ResearchWorkspaceWindowSearchService service) {
        this.service = service;
    }

    @PostMapping("/workspace-windows/search")
    ApiResponse<List<ResearchWorkspaceWindowSearchService.WindowHit>> search(
            @RequestBody Map<String, Object> request
    ) {
        if (request == null || !request.keySet().equals(FIELDS)) throw invalid();
        return ApiResponse.success(service.search(new ResearchWorkspaceWindowSearchService.SearchCommand(
                text(request, "task_id"), text(request, "worker_instance_id"), positiveInt(request, "lease_epoch"),
                positiveLong(request, "fencing_token"), stringList(request, "queries"), positiveInt(request, "limit"))));
    }

    private String text(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value instanceof String text && !text.isBlank()) return text;
        throw invalid();
    }

    private List<String> stringList(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (!(value instanceof List<?> values) || values.isEmpty()
                || values.stream().anyMatch(item -> !(item instanceof String text) || text.isBlank())) throw invalid();
        return values.stream().map(String.class::cast).toList();
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
        return new BusinessException("RESEARCH_AGENT_WORKSPACE_SEARCH_INVALID", "Workspace window search request is invalid");
    }
}
