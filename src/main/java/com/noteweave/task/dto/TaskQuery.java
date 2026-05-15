package com.noteweave.task.dto;

import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.model.TaskType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TaskQuery {

    private Long spaceId;
    private Long researchProjectId;
    private TaskType taskType;
    private TaskStatus taskStatus;
    private String targetType;
    private Long targetId;

    @Min(1)
    private int page = 1;

    @Min(1)
    @Max(100)
    private int pageSize = 20;

    private String sort = "createdAt,desc";
}
