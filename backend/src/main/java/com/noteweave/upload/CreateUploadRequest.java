package com.noteweave.upload;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateUploadRequest(
        @NotBlank @Size(max = 300) String fileName,
        @Positive long fileSize,
        @NotBlank @Size(max = 160) String mimeType,
        @Positive int chunkSize,
        @Min(1) int totalChunks
) {
}
