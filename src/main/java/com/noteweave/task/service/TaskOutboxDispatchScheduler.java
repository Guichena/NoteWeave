package com.noteweave.task.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskOutboxDispatchScheduler {

    private final TaskDispatcher taskDispatcher;

    @Value("${noteweave.task.dispatcher.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${noteweave.task.dispatcher.fixed-delay-ms:5000}")
    public void dispatchPendingMessages() {
        if (!enabled) {
            return;
        }
        try {
            taskDispatcher.dispatchPendingMessages();
        } catch (Exception ex) {
            log.warn("Scheduled task outbox dispatch failed", ex);
        }
    }
}
