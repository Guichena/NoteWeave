package com.noteweave.upload;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record CreateUploadRequest(
        @NotBlank @Size(max = 300) String fileName,
        @Positive long fileSize,
        @NotBlank @Size(max = 160) String mimeType,
        @Positive int chunkSize,
        @Min(1) int totalChunks,
        @Pattern(regexp = "[a-f0-9]{64}", message = "SHA-256 必须是64位小写十六进制字符串") String sha256
) {
    public CreateUploadRequest(String fileName, long fileSize, String mimeType, int chunkSize, int totalChunks) {
        this(fileName, fileSize, mimeType, chunkSize, totalChunks, null);
    }
}
