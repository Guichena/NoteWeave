package com.noteweave.task.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.noteweave.task.model.TaskEventType;
import com.noteweave.task.model.TaskStatus;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TaskEventResponse {
    private Long id;
    private Long taskId;
    private TaskEventType eventType;
    private TaskStatus fromStatus;
    private TaskStatus toStatus;
    private String message;
    private JsonNode payload;
    private Long createdBy;
    private LocalDateTime createdAt;
}
