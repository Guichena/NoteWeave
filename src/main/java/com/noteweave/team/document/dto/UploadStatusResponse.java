package com.noteweave.team.document.dto;

import com.noteweave.team.document.model.DocumentUploadStatus;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UploadStatusResponse {
    private Long uploadId;
    private String fileMd5;
    private DocumentUploadStatus status;
    private List<Integer> uploadedChunks;
    private Integer totalChunks;
    private double progress;
    private Long documentId;
    private Long taskId;
}
