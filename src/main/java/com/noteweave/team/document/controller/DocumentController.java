package com.noteweave.team.document.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.task.dto.TaskResponse;
import com.noteweave.team.document.dto.DocumentResponse;
import com.noteweave.team.document.service.DocumentUploadService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/team")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentUploadService documentUploadService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public ApiResponse<List<DocumentResponse>> list(@PathVariable Long knowledgeBaseId) {
        return ApiResponse.success(documentUploadService.listDocuments(currentUserProvider.getCurrentUserId(), knowledgeBaseId));
    }

    @GetMapping("/documents/{documentId}")
    public ApiResponse<DocumentResponse> get(@PathVariable Long documentId) {
        return ApiResponse.success(documentUploadService.getDocument(currentUserProvider.getCurrentUserId(), documentId));
    }

    @DeleteMapping("/documents/{documentId}")
    public ApiResponse<Void> delete(@PathVariable Long documentId) {
        documentUploadService.deleteDocument(currentUserProvider.getCurrentUserId(), documentId);
        return ApiResponse.success(null);
    }

    @PostMapping("/documents/{documentId}/reindex")
    public ApiResponse<TaskResponse> reindex(@PathVariable Long documentId) {
        return ApiResponse.success(documentUploadService.reindexDocument(currentUserProvider.getCurrentUserId(), documentId));
    }
}
