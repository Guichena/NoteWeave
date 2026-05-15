package com.noteweave.task.service;

import com.noteweave.task.model.TaskType;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TaskOutboxMessage {
    private Long taskId;
    private TaskType taskType;
    private String targetType;
    private Long targetId;
    private String idempotencyKey;
    private String createdAt;
}
