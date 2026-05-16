package com.noteweave.chat.runtime.service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class ActiveExecutionRegistry {

    private final ConcurrentMap<Long, ActiveExecution> executions = new ConcurrentHashMap<>();

    public boolean registerIfAbsent(Long sessionId, ActiveExecution execution) {
        return executions.putIfAbsent(sessionId, execution) == null;
    }

    public Optional<ActiveExecution> get(Long sessionId) {
        return Optional.ofNullable(executions.get(sessionId));
    }

    public void stop(Long sessionId) {
        ActiveExecution execution = executions.get(sessionId);
        if (execution != null) {
            execution.stop();
        }
    }

    public void remove(Long sessionId) {
        executions.remove(sessionId);
    }
}
