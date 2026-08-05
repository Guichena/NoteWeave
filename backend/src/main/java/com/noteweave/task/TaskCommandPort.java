package com.noteweave.task;

public interface TaskCommandPort {

    String createTask(
            String workspaceId,
            String taskType,
            String targetType,
            String targetId,
            String phase,
            String message
    );

    void startTask(String taskId);

    void cancelTask(String taskId, String phase, String message, String resultRef);

    void completeTask(String taskId, String phase, String message, String resultRef);

    void failTask(String taskId, String phase, String message, String errorCode, boolean retryable);
}
