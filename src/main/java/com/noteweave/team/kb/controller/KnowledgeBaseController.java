package com.noteweave.team.kb.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.team.kb.dto.CreateKnowledgeBaseRequest;
import com.noteweave.team.kb.dto.KnowledgeBaseResponse;
import com.noteweave.team.kb.dto.UpdateKnowledgeBaseRequest;
import com.noteweave.team.kb.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/team")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/spaces/{spaceId}/knowledge-bases")
    public ApiResponse<KnowledgeBaseResponse> create(
            @PathVariable Long spaceId,
            @Valid @RequestBody CreateKnowledgeBaseRequest request
    ) {
        return ApiResponse.success(knowledgeBaseService.create(currentUserProvider.getCurrentUserId(), spaceId, request));
    }

    @GetMapping("/spaces/{spaceId}/knowledge-bases")
    public ApiResponse<List<KnowledgeBaseResponse>> list(@PathVariable Long spaceId) {
        return ApiResponse.success(knowledgeBaseService.list(currentUserProvider.getCurrentUserId(), spaceId));
    }

    @GetMapping("/knowledge-bases/{kbId}")
    public ApiResponse<KnowledgeBaseResponse> get(@PathVariable("kbId") Long knowledgeBaseId) {
        return ApiResponse.success(knowledgeBaseService.get(currentUserProvider.getCurrentUserId(), knowledgeBaseId));
    }

    @PutMapping("/knowledge-bases/{kbId}")
    public ApiResponse<KnowledgeBaseResponse> update(
            @PathVariable("kbId") Long knowledgeBaseId,
            @Valid @RequestBody UpdateKnowledgeBaseRequest request
    ) {
        return ApiResponse.success(knowledgeBaseService.update(currentUserProvider.getCurrentUserId(), knowledgeBaseId, request));
    }

    @DeleteMapping("/knowledge-bases/{kbId}")
    public ApiResponse<Void> delete(@PathVariable("kbId") Long knowledgeBaseId) {
        knowledgeBaseService.archive(currentUserProvider.getCurrentUserId(), knowledgeBaseId);
        return ApiResponse.success(null);
    }
}
