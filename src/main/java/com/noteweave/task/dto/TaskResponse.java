package com.noteweave.task.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.model.TaskType;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TaskResponse {
    private Long id;
    private Long userId;
    private Long spaceId;
    private Long researchProjectId;
    private TaskType taskType;
    private String targetType;
    private Long targetId;
    private TaskStatus taskStatus;
    private boolean cancelRequested;
    private int retryCount;
    private int maxRetryCount;
    private String idempotencyKey;
    private String errorMessage;
    private JsonNode input;
    private JsonNode output;
    private String resultRefType;
    private Long resultRefId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
