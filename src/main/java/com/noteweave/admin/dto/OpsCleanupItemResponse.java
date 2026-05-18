package com.noteweave.admin.dto;

import com.noteweave.admin.model.OpsCleanupItemStatus;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OpsCleanupItemResponse {
    private Long id;
    private Long jobId;
    private String targetType;
    private Long targetId;
    private String objectKey;
    private String reason;
    private OpsCleanupItemStatus status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
