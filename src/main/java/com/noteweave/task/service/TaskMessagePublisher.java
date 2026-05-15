package com.noteweave.task.service;

public interface TaskMessagePublisher {

    void publish(TaskOutboxMessage message);
}
