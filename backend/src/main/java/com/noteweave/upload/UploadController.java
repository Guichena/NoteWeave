package com.noteweave.upload;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/workspaces/{workspaceId}/uploads")
    ApiResponse<CreateUploadResponse> createUpload(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateUploadRequest request
    ) {
        return ApiResponse.success(uploadService.createUpload(workspaceId, request));
    }

    @PutMapping("/uploads/{uploadId}/chunks/{chunkIndex}")
    ApiResponse<UploadChunkResponse> uploadChunk(
            @PathVariable String uploadId,
            @PathVariable int chunkIndex,
            @RequestHeader(value = "Content-MD5", required = false) String contentMd5,
            @RequestBody byte[] content
    ) {
        return ApiResponse.success(uploadService.acceptChunk(uploadId, chunkIndex, contentMd5, content));
    }

    @PostMapping("/uploads/{uploadId}/complete")
    ApiResponse<CompleteUploadResponse> completeUpload(@PathVariable String uploadId) {
        return ApiResponse.success(uploadService.completeUpload(uploadId));
    }
}
