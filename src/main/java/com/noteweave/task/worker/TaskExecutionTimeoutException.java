package com.noteweave.task.worker;

public class TaskExecutionTimeoutException extends RuntimeException {

    public TaskExecutionTimeoutException(String message) {
        super(message);
    }
}
