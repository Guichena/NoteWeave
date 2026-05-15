package com.noteweave.task.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LocalTaskMessagePublisher implements TaskMessagePublisher {

    private final TaskExecutionCoordinator taskExecutionCoordinator;

    @Override
    public void publish(TaskOutboxMessage message) {
        taskExecutionCoordinator.consume(message);
    }
}
