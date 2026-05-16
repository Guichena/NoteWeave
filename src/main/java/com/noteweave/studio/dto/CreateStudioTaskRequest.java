package com.noteweave.studio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.noteweave.task.model.TaskType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateStudioTaskRequest {

    @NotNull
    private Long spaceId;

    private Long researchProjectId;

    @NotNull
    private TaskType taskType;

    @NotNull
    private String sourceScopeType;

    @NotEmpty
    private List<Long> sourceIds;

    private Long createdFromSessionId;

    private Long createdFromMessageId;

    @JsonProperty("params")
    private Map<String, Object> params;
}
