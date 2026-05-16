package com.noteweave.personal.source.dto;

import com.noteweave.personal.source.model.SourceCompileStatus;
import com.noteweave.personal.source.model.SourceImportStatus;
import com.noteweave.personal.source.model.SourceType;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SourceResponse {
    private Long id;
    private Long spaceId;
    private Long researchProjectId;
    private String title;
    private SourceType sourceType;
    private String url;
    private String objectKey;
    private String rawTextObjectKey;
    private String parsedTextObjectKey;
    private String contentHash;
    private SourceImportStatus importStatus;
    private SourceCompileStatus compileStatus;
    private int tokenCount;
    private String errorMessage;
    private Long createdBy;
    private Long taskId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
