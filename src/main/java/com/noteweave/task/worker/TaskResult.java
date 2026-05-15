package com.noteweave.task.worker;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TaskResult {
    private Object output;
    private String resultRefType;
    private Long resultRefId;

    public static TaskResult success(Object output) {
        return TaskResult.builder()
                .output(output)
                .build();
    }
}
