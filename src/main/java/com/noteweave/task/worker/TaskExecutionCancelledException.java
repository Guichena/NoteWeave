package com.noteweave.task.worker;

public class TaskExecutionCancelledException extends RuntimeException {

    public TaskExecutionCancelledException(String message) {
        super(message);
    }
}
