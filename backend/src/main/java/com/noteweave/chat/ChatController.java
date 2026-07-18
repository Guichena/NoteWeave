package com.noteweave.chat;

import com.noteweave.answer.AnswerEventResponse;
import com.noteweave.answer.AnswerEvidenceManifestResponse;
import com.noteweave.answer.AnswerRunResponse;
import com.noteweave.answer.AnswerRunService;
import com.noteweave.answer.AnswerCancellationRegistry;
import com.noteweave.common.ApiResponse;
import com.noteweave.common.BusinessException;
import com.noteweave.conversation.ConversationTurnModule;
import com.noteweave.conversation.SubmitTurnCommand;
import com.noteweave.conversation.TurnReceipt;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v2")
public class ChatController {

    private final ChatService chatService;
    private final ConversationTurnModule conversationTurnModule;
    private final Executor answerIoExecutor;
    private final Executor sseConnectionExecutor;
    private final AnswerRunService answerRunService;
    private final AnswerCancellationRegistry answerCancellationRegistry;

    public ChatController(
            ChatService chatService,
            ConversationTurnModule conversationTurnModule,
            @Qualifier("answerIoExecutor") Executor answerIoExecutor,
            @Qualifier("sseConnectionExecutor") Executor sseConnectionExecutor,
            AnswerRunService answerRunService,
            AnswerCancellationRegistry answerCancellationRegistry
    ) {
        this.chatService = chatService;
        this.conversationTurnModule = conversationTurnModule;
        this.answerIoExecutor = answerIoExecutor;
        this.sseConnectionExecutor = sseConnectionExecutor;
        this.answerRunService = answerRunService;
        this.answerCancellationRegistry = answerCancellationRegistry;
    }

    @PostMapping("/conversations/{conversationId}/messages")
    ApiResponse<TurnReceipt> sendMessage(
            @PathVariable String conversationId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        TurnReceipt result = conversationTurnModule.submitNewTurn(
                SubmitTurnCommand.from(conversationId, request));
        com.noteweave.answer.AnswerRunRef run = "ANSWER".equals(result.executionKind())
                ? answerRunService.requireByAssistantRequest(result.assistantRequestId())
                : null;
        if (run != null) {
            try {
                answerIoExecutor.execute(() -> {
                    try {
                        chatService.startRun(run.workspaceId(), run.runId());
                    } catch (BusinessException ex) {
                        if (!"ANSWER_RUN_ALREADY_STREAMING".equals(ex.code())) {
                            throw ex;
                        }
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // The canonical SSE endpoint can still recover a run when the execution queue is saturated.
            }
        }
        return ApiResponse.success(result);
    }

    @GetMapping(value = "/chat/requests/{assistantRequestId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable String assistantRequestId, HttpServletResponse response) {
        chatService.requireStreamAccess(assistantRequestId);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8");
        SseEmitter emitter = new SseEmitter(120_000L);
        try {
            answerIoExecutor.execute(() -> {
                try {
                    chatService.stream(assistantRequestId, event -> send(emitter, event));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            send(emitter, new ChatStreamEvent("0", "chat.failed", "回答队列已满，请稍后重试"));
            emitter.complete();
        }
        return emitter;
    }

    @GetMapping("/workspaces/{workspaceId}/answer-runs/{runId}")
    ApiResponse<AnswerRunResponse> getAnswerRun(
            @PathVariable String workspaceId,
            @PathVariable String runId
    ) {
        return ApiResponse.success(answerRunService.get(workspaceId, runId));
    }

    @GetMapping("/workspaces/{workspaceId}/answer-runs/{runId}/evidence")
    ApiResponse<AnswerEvidenceManifestResponse> getAnswerEvidence(
            @PathVariable String workspaceId,
            @PathVariable String runId
    ) {
        return ApiResponse.success(answerRunService.evidenceManifest(workspaceId, runId));
    }

    @DeleteMapping("/workspaces/{workspaceId}/answer-runs/{runId}")
    ApiResponse<AnswerRunResponse> cancelAnswerRun(
            @PathVariable String workspaceId,
            @PathVariable String runId
    ) {
        AnswerRunResponse cancelled = answerRunService.cancel(workspaceId, runId);
        answerCancellationRegistry.cancel(runId);
        return ApiResponse.success(cancelled);
    }

    @GetMapping(
            value = "/workspaces/{workspaceId}/answer-runs/{runId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    SseEmitter streamAnswerRun(
            @PathVariable String workspaceId,
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response
    ) {
        long cursor = resolveCursor(after, lastEventId);
        answerRunService.get(workspaceId, runId);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8");
        SseEmitter emitter = new SseEmitter(120_000L);
        try {
            sseConnectionExecutor.execute(() -> {
                try {
                    java.util.List<AnswerEventResponse> stored = answerRunService.eventsAfter(workspaceId, runId, cursor);
                    java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong(cursor);
                    for (AnswerEventResponse event : stored) {
                        sequence.set(Math.max(sequence.get(), event.sequence()));
                        if (!isTerminalEvent(event.eventType())) {
                            send(emitter, new ChatStreamEvent(
                                    Long.toString(event.sequence()), event.eventType(), event.payloadJson()));
                        }
                    }
                    AnswerRunResponse snapshot = answerRunService.get(workspaceId, runId);
                    if ("COMPLETED".equals(snapshot.status())) {
                        long terminalSequence = answerRunService.currentEventSequence(workspaceId, runId);
                        if (cursor < terminalSequence) {
                            send(emitter, new ChatStreamEvent(
                                    Long.toString(terminalSequence), "answer.snapshot", snapshot.content()));
                            send(emitter, new ChatStreamEvent(
                                    Long.toString(terminalSequence + 1), "answer.completed", snapshot.answerMessageId()));
                        }
                    } else if ("CANCELLED".equals(snapshot.status())) {
                        long terminalSequence = answerRunService.currentEventSequence(workspaceId, runId);
                        if (cursor < terminalSequence) {
                            send(emitter, new ChatStreamEvent(
                                    Long.toString(terminalSequence), "answer.cancelled", "CANCELLED"));
                        }
                    } else if ("FAILED".equals(snapshot.status())) {
                        long terminalSequence = answerRunService.currentEventSequence(workspaceId, runId);
                        if (cursor < terminalSequence) {
                            send(emitter, new ChatStreamEvent(
                                    Long.toString(terminalSequence), "answer.failed", snapshot.errorMessage()));
                        }
                    } else {
                        if (!answerRunService.hasActiveStream(workspaceId, runId)) {
                            try {
                                answerIoExecutor.execute(() -> {
                                    try {
                                        chatService.streamRun(workspaceId, runId, ignored -> { });
                                    } catch (BusinessException ex) {
                                        if (!"ANSWER_RUN_ALREADY_STREAMING".equals(ex.code())) {
                                            throw ex;
                                        }
                                    }
                                });
                            } catch (RejectedExecutionException ex) {
                                send(emitter, new ChatStreamEvent(
                                        Long.toString(sequence.incrementAndGet()),
                                        "answer.failed",
                                        "回答生成队列已满，请稍后重试"
                                ));
                                emitter.complete();
                                return;
                            }
                        }
                        chatService.followRun(workspaceId, runId, cursor, event -> send(emitter,
                                new ChatStreamEvent(Long.toString(event.sequence()), event.eventType(), event.data())));
                    }
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            send(emitter, new ChatStreamEvent("0", "answer.failed", "回答队列已满，请稍后重试"));
            emitter.complete();
        }
        return emitter;
    }

    private String canonicalEvent(String legacyEvent) {
        return switch (legacyEvent) {
            case "chat.delta" -> "answer.delta";
            case "chat.citation" -> "citation.upsert";
            case "chat.completed" -> "answer.completed";
            case "chat.failed" -> "answer.failed";
            default -> legacyEvent;
        };
    }

    private boolean isTerminalEvent(String eventType) {
        return "answer.completed".equals(eventType) || "answer.failed".equals(eventType)
                || "answer.cancelled".equals(eventType);
    }

    private long resolveCursor(long after, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return Math.max(0, after);
        }
        try {
            return Math.max(Math.max(0, after), Long.parseLong(lastEventId.trim()));
        } catch (NumberFormatException ex) {
            throw new BusinessException("ANSWER_EVENT_CURSOR_INVALID", "Last-Event-ID 必须是非负整数");
        }
    }

    private void send(SseEmitter emitter, ChatStreamEvent event) {
        try {
            emitter.send(SseEmitter.event().id(event.id()).name(event.event()).data(event.data()));
        } catch (IOException ex) {
            throw new IllegalStateException("SSE client disconnected", ex);
        }
    }
}
