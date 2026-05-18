package com.noteweave.admin.dto;

import com.noteweave.admin.model.OpsCleanupJobType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CleanupScanRequest {
    @NotNull
    private OpsCleanupJobType jobType;
    private String targetType;
    private Long targetId;
    private Integer retentionDays;
}
