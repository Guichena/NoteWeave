package com.noteweave.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.ApiResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.List;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v2/tasks")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final TaskService taskService;
    private final Executor sseConnectionExecutor;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public TaskController(
            TaskService taskService,
            @Qualifier("sseConnectionExecutor") Executor sseConnectionExecutor,
            ObjectMapper objectMapper
    ) {
        this(taskService, sseConnectionExecutor, objectMapper, Metrics.globalRegistry);
    }

    @Autowired
    public TaskController(
            TaskService taskService,
            @Qualifier("sseConnectionExecutor") Executor sseConnectionExecutor,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this.taskService = taskService;
        this.sseConnectionExecutor = sseConnectionExecutor;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @GetMapping("/{taskId}")
    ApiResponse<TaskResponse> getTask(@PathVariable String taskId) {
        return ApiResponse.success(taskService.getTask(taskId));
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter streamTaskEvents(
            @PathVariable String taskId,
            @RequestParam(required = false) String afterEventId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        TaskResponse initialTask = taskService.getTask(taskId);
        String initialCursor = resolveCursor(afterEventId, lastEventId);
        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(ignored -> open.set(false));
        try {
            sseConnectionExecutor.execute(() -> followTaskEvents(
                    emitter, taskId, initialCursor, initialTask.taskStatus(), open));
        } catch (RejectedExecutionException ex) {
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    @GetMapping("/{taskId}/event-history")
    ApiResponse<List<TaskEventResponse>> getTaskEventHistory(
            @PathVariable String taskId,
            @RequestParam(required = false) String afterEventId,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.success(taskService.listEvents(taskId, afterEventId, limit));
    }

    private void followTaskEvents(
            SseEmitter emitter,
            String taskId,
            String initialCursor,
            String initialStatus,
            AtomicBoolean open
    ) {
        String cursor = initialCursor;
        boolean terminal = isTerminalStatus(initialStatus);
        long deadline = System.nanoTime() + Duration.ofSeconds(115).toNanos();
        long pollDelayMillis = 500;
        long nextHeartbeat = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        try {
            while (open.get() && System.nanoTime() < deadline) {
                TaskService.TaskStreamPoll poll = taskService.pollAuthorizedStream(taskId, cursor);
                List<TaskEventResponse> events = poll.events();
                for (TaskEventResponse event : events) {
                    send(emitter, event);
                    cursor = event.eventId();
                    terminal = terminal || isTerminalEvent(event.eventType());
                }
                if (terminal) {
                    emitter.complete();
                    return;
                }
                if (isTerminalStatus(poll.currentStatus())) {
                    sendTerminalSnapshot(emitter, poll.currentStatus());
                    emitter.complete();
                    return;
                }
                if (events.isEmpty() && System.nanoTime() >= nextHeartbeat) {
                    sendHeartbeat(emitter);
                    nextHeartbeat = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                }
                pollDelayMillis = events.isEmpty()
                        ? Math.min(2_000, pollDelayMillis * 2)
                        : 500;
                LockSupport.parkNanos(Duration.ofMillis(pollDelayMillis).toNanos());
                if (Thread.currentThread().isInterrupted()) {
                    emitter.complete();
                    return;
                }
            }
            emitter.complete();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    private void send(SseEmitter emitter, TaskEventResponse event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(event.eventId())
                .name(toSseEventName(event.eventType()))
                .data(Map.of(
                        "message", event.message(),
                        "payload", readPayload(event.payloadJson()),
                        "event_type", event.eventType(),
                        "created_at", event.createdAt().toString()
                )));
    }

    private void sendTerminalSnapshot(SseEmitter emitter, String status) throws IOException {
        String eventType = switch (status) {
            case "COMPLETED" -> "TASK_COMPLETED";
            case "FAILED" -> "TASK_FAILED";
            case "CANCELLED" -> "TASK_CANCELLED";
            default -> throw new IllegalArgumentException("Task status is not terminal: " + status);
        };
        emitter.send(SseEmitter.event()
                .name(toSseEventName(eventType))
                .data(Map.of(
                        "message", "authoritative terminal task snapshot",
                        "payload", Map.of("snapshot", true, "task_status", status),
                        "event_type", eventType,
                        "created_at", Instant.now().toString()
                )));
    }

    private void sendHeartbeat(SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event()
                .name("task.heartbeat")
                .data(Map.of(
                        "message", "task stream heartbeat",
                        "payload", Map.of("heartbeat", true),
                        "event_type", "TASK_HEARTBEAT",
                        "created_at", Instant.now().toString()
                )));
    }

    private Map<String, Object> readPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<>() { });
        } catch (Exception ex) {
            meterRegistry.counter("noteweave.task.event.payload.parse_error").increment();
            log.warn("Task event payload is not valid JSON; returning an explicit parse-error marker");
            return Map.of(
                    "payload_parse_error", true,
                    "payload_error_code", "TASK_EVENT_PAYLOAD_INVALID"
            );
        }
    }

    private String resolveCursor(String afterEventId, String lastEventId) {
        if (lastEventId != null && !lastEventId.isBlank()) {
            return lastEventId.trim();
        }
        return afterEventId == null ? "" : afterEventId.trim();
    }

    private boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private boolean isTerminalEvent(String eventType) {
        return "TASK_COMPLETED".equals(eventType)
                || "TASK_FAILED".equals(eventType)
                || "TASK_CANCELLED".equals(eventType);
    }

    private String toSseEventName(String eventType) {
        return switch (eventType) {
            case "TASK_CREATED" -> "task.status";
            case "TASK_COMPLETED" -> "task.completed";
            case "TASK_FAILED" -> "task.failed";
            case "TASK_CANCELLED" -> "task.cancelled";
            case "TASK_HEARTBEAT" -> "task.heartbeat";
            default -> "task.progress";
        };
    }
}
