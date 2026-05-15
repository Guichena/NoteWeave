package com.noteweave.task.service;

import com.noteweave.task.model.TaskType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskOutboxMessage {
    private Long taskId;
    private TaskType taskType;
    private String targetType;
    private Long targetId;
    private String idempotencyKey;
    private String inputJson;
    private String createdAt;
}
