package com.noteweave.upload;

import com.noteweave.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/uploads/formats")
    ApiResponse<java.util.List<UploadFormatResponse>> formats() {
        return ApiResponse.success(uploadService.formats());
    }

    @GetMapping("/uploads/{uploadId}")
    ApiResponse<UploadStatusResponse> uploadStatus(@PathVariable String uploadId) {
        return ApiResponse.success(uploadService.getUpload(uploadId));
    }

    @PutMapping("/uploads/{uploadId}/chunks/{chunkIndex}")
    ApiResponse<UploadChunkResponse> uploadChunk(
            @PathVariable String uploadId,
            @PathVariable int chunkIndex,
            @RequestHeader(value = "Content-MD5", required = false) String contentMd5,
            HttpServletRequest request
    ) {
        byte[] content = UploadRequestBodyReader.read(request);
        return ApiResponse.success(uploadService.acceptChunk(uploadId, chunkIndex, contentMd5, content));
    }

    @PostMapping("/uploads/{uploadId}/complete")
    ApiResponse<CompleteUploadResponse> completeUpload(@PathVariable String uploadId) {
        return ApiResponse.success(uploadService.completeUpload(uploadId));
    }
}
