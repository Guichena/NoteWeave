package com.noteweave.search.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.search.dto.SearchDebugResponse;
import com.noteweave.search.service.SearchDebugService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/team")
@RequiredArgsConstructor
public class SearchDebugController {

    private final SearchDebugService searchDebugService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/search")
    public ApiResponse<SearchDebugResponse> search(
            @PathVariable Long knowledgeBaseId,
            @RequestParam String keyword
    ) {
        return ApiResponse.success(searchDebugService.search(currentUserProvider.getCurrentUserId(), knowledgeBaseId, keyword));
    }
}
