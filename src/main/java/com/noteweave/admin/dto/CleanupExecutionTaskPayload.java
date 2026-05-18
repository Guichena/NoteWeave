package com.noteweave.admin.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CleanupExecutionTaskPayload {
    private Long jobId;
    private Long operatorId;
}
