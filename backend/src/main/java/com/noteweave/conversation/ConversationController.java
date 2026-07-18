package com.noteweave.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.AnswerRunService;
import com.noteweave.answer.ConversationEventMux;
import com.noteweave.answer.ConversationLiveEvent;
import com.noteweave.common.ApiResponse;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Json;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final AnswerRunService answerRunService;
    private final ConversationEventMux conversationEventMux;
    private final Executor sseConnectionExecutor;
    private final ObjectMapper objectMapper;
    private final ConversationTurnModule conversationTurnModule;
    private final ConversationSegmentBuildService conversationSegmentBuildService;
    private final ConversationMessageDeletionService conversationMessageDeletionService;

    public ConversationController(
            ConversationService conversationService,
            AnswerRunService answerRunService,
            ConversationEventMux conversationEventMux,
            @Qualifier("sseConnectionExecutor") Executor sseConnectionExecutor,
            ObjectMapper objectMapper,
            ConversationTurnModule conversationTurnModule,
            ConversationSegmentBuildService conversationSegmentBuildService,
            ConversationMessageDeletionService conversationMessageDeletionService
    ) {
        this.conversationService = conversationService;
        this.answerRunService = answerRunService;
        this.conversationEventMux = conversationEventMux;
        this.sseConnectionExecutor = sseConnectionExecutor;
        this.objectMapper = objectMapper;
        this.conversationTurnModule = conversationTurnModule;
        this.conversationSegmentBuildService = conversationSegmentBuildService;
        this.conversationMessageDeletionService = conversationMessageDeletionService;
    }

    @PostMapping
    ApiResponse<ConversationResponse> createConversation(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateConversationRequest request
    ) {
        return ApiResponse.success(conversationService.createConversation(workspaceId, request));
    }

    @GetMapping
    ApiResponse<List<ConversationSummaryResponse>> listConversations(@PathVariable String workspaceId) {
        return ApiResponse.success(conversationService.listConversations(workspaceId));
    }

    @GetMapping("/{conversationId}/messages")
    ApiResponse<List<ConversationMessageResponse>> listMessages(
            @PathVariable String workspaceId,
            @PathVariable String conversationId,
            @RequestParam(name = "after_seq", defaultValue = "0") int afterSequence,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return ApiResponse.success(conversationService.listMessages(
                workspaceId, conversationId, afterSequence, limit));
    }

    @GetMapping("/{conversationId}/turn-submissions/{clientRequestId}")
    ApiResponse<TurnSubmissionResponse> getTurnSubmission(
            @PathVariable String workspaceId,
            @PathVariable String conversationId,
            @PathVariable String clientRequestId
    ) {
        return ApiResponse.success(conversationTurnModule.getSubmission(
                workspaceId, conversationId, clientRequestId));
    }

    @GetMapping("/{conversationId}/segment-summary-builds")
    ApiResponse<List<ConversationSegmentSummaryBuildResponse>> listSegmentSummaryBuilds(
            @PathVariable String workspaceId,
            @PathVariable String conversationId
    ) {
        return ApiResponse.success(conversationSegmentBuildService.listBuilds(workspaceId, conversationId));
    }

    @DeleteMapping("/{conversationId}/messages/{messageId}")
    ApiResponse<DeletedConversationMessageResponse> deleteMessage(
            @PathVariable String workspaceId, @PathVariable String conversationId, @PathVariable String messageId
    ) {
        return ApiResponse.success(conversationMessageDeletionService.delete(workspaceId, conversationId, messageId));
    }

    @GetMapping(value = "/{conversationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter streamConversation(
            @PathVariable String workspaceId,
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(name = "close_on_terminal", defaultValue = "false") boolean closeOnTerminal,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response
    ) {
        long cursor = resolveCursor(after, lastEventId);
        conversationService.requireConversation(workspaceId, conversationId);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8");
        SseEmitter emitter = new SseEmitter(120_000L);
        try {
            sseConnectionExecutor.execute(() -> {
                try {
                    ConversationStreamSnapshot snapshot = new ConversationStreamSnapshot(
                            conversationId,
                            answerRunService.listConversationRuns(workspaceId, conversationId, 20)
                    );
                    emitter.send(SseEmitter.event()
                            .name("conversation.snapshot")
                            .data(Json.write(objectMapper, snapshot)));
                    conversationEventMux.follow(
                            conversationId,
                            cursor,
                            event -> send(emitter, event),
                            Duration.ofSeconds(115),
                            closeOnTerminal
                    );
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    private long resolveCursor(long after, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return Math.max(0, after);
        }
        try {
            return Math.max(Math.max(0, after), Long.parseLong(lastEventId.trim()));
        } catch (NumberFormatException ex) {
            throw new BusinessException(
                    "CONVERSATION_EVENT_CURSOR_INVALID",
                    "Last-Event-ID must be a non-negative integer"
            );
        }
    }

    private void send(SseEmitter emitter, ConversationLiveEvent event) {
        try {
            String payload = Json.write(objectMapper, Map.of(
                    "run_id", event.runId(),
                    "run_sequence", event.runSequence(),
                    "data", event.data(),
                    "occurred_at", event.occurredAt()
            ));
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.sequence()))
                    .name(event.eventType())
                    .data(payload));
        } catch (IOException ex) {
            throw new IllegalStateException("SSE client disconnected", ex);
        }
    }
}
