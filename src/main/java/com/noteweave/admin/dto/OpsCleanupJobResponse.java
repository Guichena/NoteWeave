package com.noteweave.admin.dto;

import com.noteweave.admin.model.OpsCleanupJobStatus;
import com.noteweave.admin.model.OpsCleanupJobType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OpsCleanupJobResponse {
    private Long id;
    private OpsCleanupJobType jobType;
    private OpsCleanupJobStatus status;
    private String targetType;
    private Long targetId;
    private int scanCount;
    private int cleanupCount;
    private String errorMessage;
    private Long startedBy;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<OpsCleanupItemResponse> items;
}
