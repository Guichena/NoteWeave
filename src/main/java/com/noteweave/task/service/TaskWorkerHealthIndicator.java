package com.noteweave.task.service;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class TaskWorkerHealthIndicator implements HealthIndicator {

    private final TaskWorkerRegistry taskWorkerRegistry;

    public TaskWorkerHealthIndicator(TaskWorkerRegistry taskWorkerRegistry) {
        this.taskWorkerRegistry = taskWorkerRegistry;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("registeredWorkers", taskWorkerRegistry.registeredTypes())
                .withDetail("workerCount", taskWorkerRegistry.registeredTypes().size())
                .build();
    }
}
