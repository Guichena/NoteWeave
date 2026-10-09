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

    /** 记录多阶段任务进入的新阶段；任务不在运行中时忽略，不抛出异常。 */
    default void recordStage(String taskId, String phase, String message) {
    }
}
