package com.noteweave.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.task.model.TaskEventType;
import com.noteweave.task.model.TaskOutbox;
import com.noteweave.task.model.TaskOutboxStatus;
import com.noteweave.task.repository.TaskOutboxRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDispatcher {

    private static final long RETRY_DELAY_SECONDS = 30L;

    private final TaskOutboxRepository taskOutboxRepository;
    private final TaskEventService taskEventService;
    private final TaskMessagePublisher taskMessagePublisher;
    private final ObjectMapper objectMapper;

    public int dispatchPendingMessages() {
        List<TaskOutbox> outboxes = taskOutboxRepository.findDispatchable(LocalDateTime.now());
        for (TaskOutbox outbox : outboxes) {
            dispatch(outbox.getId());
        }
        return outboxes.size();
    }

    protected void dispatch(Long outboxId) {
        TaskOutbox outbox = taskOutboxRepository.findById(outboxId).orElse(null);
        if (outbox == null || outbox.getStatus() == TaskOutboxStatus.SENT) {
            return;
        }
        if (outbox.getStatus() == TaskOutboxStatus.FAILED
                && outbox.getNextRetryAt() != null
                && outbox.getNextRetryAt().isAfter(LocalDateTime.now())) {
            return;
        }

        try {
            taskEventService.appendEvent(
                    outbox.getTaskId(),
                    TaskEventType.TASK_DISPATCHED,
                    null,
                    null,
                    "Task dispatched to worker",
                    null,
                    null
            );
            taskMessagePublisher.publish(objectMapper.readValue(outbox.getPayloadJson(), TaskOutboxMessage.class));
            outbox.setStatus(TaskOutboxStatus.SENT);
            outbox.setSentAt(LocalDateTime.now());
            outbox.setNextRetryAt(null);
            taskOutboxRepository.save(outbox);
            taskEventService.appendEvent(
                    outbox.getTaskId(),
                    TaskEventType.OUTBOX_SENT,
                    null,
                    null,
                    "Task outbox dispatched",
                    null,
                    null
            );
        } catch (Exception ex) {
            log.warn("Failed to dispatch task outbox {}", outboxId, ex);
            outbox.setStatus(TaskOutboxStatus.FAILED);
            outbox.setRetryCount(outbox.getRetryCount() + 1);
            outbox.setNextRetryAt(LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS));
            taskOutboxRepository.save(outbox);
            taskEventService.appendEvent(
                    outbox.getTaskId(),
                    TaskEventType.OUTBOX_SEND_FAILED,
                    null,
                    null,
                    ex.getMessage(),
                    null,
                    null
            );
        }
    }
}
