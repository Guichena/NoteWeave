package com.noteweave.team.document.dto;

import com.noteweave.team.document.model.DocumentUploadStatus;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class InitUploadResponse {
    private Long uploadId;
    private Long documentId;
    private String fileMd5;
    private List<Integer> uploadedChunks;
    private Integer totalChunks;
    private boolean instantUpload;
    private DocumentUploadStatus status;
}
