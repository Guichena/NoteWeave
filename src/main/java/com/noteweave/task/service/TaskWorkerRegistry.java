package com.noteweave.task.service;

import com.noteweave.task.model.TaskType;
import com.noteweave.task.worker.TaskWorker;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class TaskWorkerRegistry {

    private final Map<TaskType, TaskWorker> workers;

    public TaskWorkerRegistry(List<TaskWorker> workers) {
        this.workers = new EnumMap<>(TaskType.class);
        for (TaskWorker worker : workers) {
            this.workers.put(worker.taskType(), worker);
        }
    }

    public TaskWorker find(TaskType taskType) {
        return workers.get(taskType);
    }

    public Set<TaskType> registeredTypes() {
        return workers.keySet();
    }
}
