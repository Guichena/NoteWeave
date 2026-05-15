package com.noteweave.team.document.dto;

import com.noteweave.team.document.model.DocumentUploadStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MergeUploadResponse {
    private Long uploadId;
    private Long documentId;
    private Long taskId;
    private DocumentUploadStatus status;
    private String objectKey;
}
