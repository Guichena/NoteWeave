package com.noteweave.task.worker;

import com.noteweave.task.model.TaskType;

public interface TaskWorker {

    TaskType taskType();

    TaskResult execute(TaskExecutionContext context);
}
