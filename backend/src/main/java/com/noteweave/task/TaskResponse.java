package com.noteweave.task;

public record TaskResponse(
        String taskId,
        String taskType,
        String taskStatus,
        String progressPhase,
        String progressMessage,
        String resultRef,
        String errorMessage,
        String targetType,
        String targetId
) {
}
