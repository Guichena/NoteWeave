package com.noteweave.team.document.dto;

import com.noteweave.team.document.model.DocumentStatus;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DocumentResponse {
    private Long id;
    private Long spaceId;
    private Long knowledgeBaseId;
    private Long fileObjectId;
    private String title;
    private String sourceType;
    private String objectKey;
    private String originalFilename;
    private String contentHash;
    private DocumentStatus status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
