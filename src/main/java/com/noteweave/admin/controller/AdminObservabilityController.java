package com.noteweave.admin.controller;

import com.noteweave.chat.dto.RetrievalTraceDetailResponse;
import com.noteweave.chat.service.RetrievalTraceService;
import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.api.PageResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.llm.dto.LlmCallLogQuery;
import com.noteweave.llm.dto.LlmCallLogResponse;
import com.noteweave.llm.service.LlmCallLogService;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.prompt.dto.CreatePromptVersionRequest;
import com.noteweave.prompt.dto.PromptVersionResponse;
import com.noteweave.prompt.service.PromptVersionService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminObservabilityController {

    private final CurrentUserProvider currentUserProvider;
    private final ResourceAccessService resourceAccessService;
    private final PromptVersionService promptVersionService;
    private final LlmCallLogService llmCallLogService;
    private final RetrievalTraceService retrievalTraceService;

    @GetMapping("/prompt-versions")
    public ApiResponse<List<PromptVersionResponse>> listPromptVersions(@RequestParam(required = false) String scene) {
        resourceAccessService.requireAdmin(currentUserProvider.getCurrentUserId());
        return ApiResponse.success(promptVersionService.list(scene));
    }

    @PostMapping("/prompt-versions")
    public ApiResponse<PromptVersionResponse> createPromptVersion(@Valid @RequestBody CreatePromptVersionRequest request) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        return ApiResponse.success(promptVersionService.create(userId, request));
    }

    @PostMapping("/prompt-versions/{promptVersionId}/activate")
    public ApiResponse<PromptVersionResponse> activatePromptVersion(@PathVariable Long promptVersionId) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        return ApiResponse.success(promptVersionService.activate(userId, promptVersionId));
    }

    @GetMapping("/llm-call-logs")
    public ApiResponse<PageResponse<LlmCallLogResponse>> searchLlmCallLogs(LlmCallLogQuery query) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        return ApiResponse.success(llmCallLogService.search(query));
    }

    @GetMapping("/retrieval-traces/{traceId}")
    public ApiResponse<RetrievalTraceDetailResponse> getRetrievalTrace(@PathVariable Long traceId) {
        resourceAccessService.requireAdmin(currentUserProvider.getCurrentUserId());
        return ApiResponse.success(retrievalTraceService.get(currentUserProvider.getCurrentUserId(), traceId));
    }
}
