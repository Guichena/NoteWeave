package com.noteweave.task.service;

import com.noteweave.task.model.TaskType;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TaskCreateCommand {
    private Long userId;
    private Long spaceId;
    private Long researchProjectId;
    private TaskType taskType;
    private String targetType;
    private Long targetId;
    private String idempotencyKey;
    private Object input;
    @Builder.Default
    private int maxRetryCount = 3;
}
