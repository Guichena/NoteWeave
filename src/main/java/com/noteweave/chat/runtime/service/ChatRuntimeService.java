package com.noteweave.chat.runtime.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatMessageRole;
import com.noteweave.chat.model.ChatMessageStatus;
import com.noteweave.chat.model.ChatMessageType;
import com.noteweave.chat.model.ChatRuntimeStatus;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.model.ChatSessionKind;
import com.noteweave.chat.model.ChatSessionType;
import com.noteweave.chat.repository.ChatMessageRepository;
import com.noteweave.chat.repository.ChatSessionRepository;
import com.noteweave.chat.repository.ChatSessionScopeRepository;
import com.noteweave.chat.runtime.config.ChatRuntimeProperties;
import com.noteweave.chat.runtime.protocol.ClientEventEnvelope;
import com.noteweave.chat.runtime.protocol.ServerEventEnvelope;
import com.noteweave.citation.dto.CitationResponse;
import com.noteweave.citation.service.CitationService;
import com.noteweave.common.api.RequestIdHolder;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.llm.dto.LlmMessage;
import com.noteweave.llm.dto.LlmOptions;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.service.LlmClient;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.team.kb.model.KnowledgeBaseStatus;
import com.noteweave.team.kb.repository.KnowledgeBaseRepository;
import com.noteweave.team.rag.config.RagProperties;
import com.noteweave.team.rag.evidence.EvidenceItem;
import com.noteweave.team.rag.evidence.EvidenceOptions;
import com.noteweave.team.rag.evidence.EvidencePostProcessor;
import com.noteweave.team.rag.prompt.PromptMessages;
import com.noteweave.team.rag.prompt.TeamRagPromptBuilder;
import com.noteweave.team.rag.retriever.Bm25Retriever;
import com.noteweave.team.rag.retriever.TeamRetrievalQuery;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRuntimeService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionScopeRepository chatSessionScopeRepository;
    private final ResourceAccessService resourceAccessService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final Bm25Retriever bm25Retriever;
    private final EvidencePostProcessor evidencePostProcessor;
    private final TeamRagPromptBuilder teamRagPromptBuilder;
    private final LlmClient llmClient;
    private final CitationService citationService;
    private final ActiveExecutionRegistry activeExecutionRegistry;
    private final ChatRuntimeStateStore chatRuntimeStateStore;
    private final ContextReadRouter contextReadRouter;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    private final ChatRuntimeProperties chatRuntimeProperties;
    private final PlatformTransactionManager transactionManager;
    private final Executor applicationTaskExecutor;

    public void onConnect(Long userId, WebSocketSession session) {
        send(session, ServerEventEnvelope.builder()
                .event("chat.connected")
                .payload(objectNode(Map.of("userId", userId)))
                .build());
    }

    public void handleClientEvent(Long userId, WebSocketSession socketSession, ClientEventEnvelope event) {
        if ("chat.message".equals(event.getEvent())) {
            applicationTaskExecutor.execute(() -> processChatMessage(userId, socketSession, event));
            return;
        }
        if ("chat.stop".equals(event.getEvent())) {
            stopExecution(userId, socketSession, event);
            return;
        }
        if ("chat.resume".equals(event.getEvent())) {
            resume(userId, socketSession, event);
        }
    }

    public void resume(Long userId, WebSocketSession socketSession, ClientEventEnvelope event) {
        ChatSession session = getRequiredSession(event.getSessionId());
        resourceAccessService.requireViewSpace(userId, session.getSpaceId());
        List<ServerEventEnvelope> replay = chatRuntimeStateStore.readEventsAfter(session.getId(), event.getAck() == null ? 0L : event.getAck());
        for (ServerEventEnvelope envelope : replay) {
            send(socketSession, envelope);
        }
        RuntimeSnapshot snapshot = chatRuntimeStateStore.readSnapshot(session.getId()).orElse(null);
        send(socketSession, ServerEventEnvelope.builder()
                .event("chat.restored")
                .sessionId(session.getId())
                .streamId(snapshot == null || snapshot.streamState() == null ? null : snapshot.streamState().getStreamId())
                .payload(objectNode(Map.of(
                        "runtimeStatus", snapshot == null || snapshot.runtimeState() == null || snapshot.runtimeState().getRuntimeStatus() == null
                                ? session.getRuntimeStatus().name()
                                : snapshot.runtimeState().getRuntimeStatus().name(),
                        "partialContent", snapshot == null || snapshot.streamState() == null || snapshot.streamState().getPartialContent() == null
                                ? ""
                                : snapshot.streamState().getPartialContent()
                )))
                .build());
    }

    public void stopExecution(Long userId, WebSocketSession socketSession, ClientEventEnvelope event) {
        ChatSession session = getRequiredSession(event.getSessionId());
        resourceAccessService.requireAskQuestion(userId, session.getSpaceId());
        ActiveExecution execution = activeExecutionRegistry.get(session.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_RUNTIME_NOT_FOUND));
        execution.stop();
        updateSessionRuntimeStatus(session.getId(), ChatRuntimeStatus.STOPPED);
        chatRuntimeStateStore.writeStreamState(session.getId(), StreamState.builder()
                .streamId(execution.getStreamId())
                .status(ChatRuntimeStatus.STOPPED)
                .partialContent(readPartialContent(session.getId()))
                .build());
        ServerEventEnvelope stopped = chatRuntimeStateStore.appendEvent(session.getId(), ServerEventEnvelope.builder()
                .event("chat.stopped")
                .requestId(event.getRequestId())
                .streamId(execution.getStreamId())
                .sessionId(session.getId())
                .ack(event.getAck())
                .payload(objectNode(Map.of("runtimeStatus", ChatRuntimeStatus.STOPPED.name())))
                .build());
        send(socketSession, stopped);
    }

    public void processChatMessage(Long userId, WebSocketSession socketSession, ClientEventEnvelope event) {
        JsonNode payload = event.getPayload();
        Long sessionId = event.getSessionId();
        ChatSession session = getRequiredSession(sessionId);
        resourceAccessService.requireAskQuestion(userId, session.getSpaceId());
        if (session.getSessionType() != ChatSessionType.TEAM_CHAT) {
            throw new BusinessException(ErrorCode.CHAT_SESSION_TYPE_UNSUPPORTED);
        }
        if (activeExecutionRegistry.get(sessionId).isPresent()) {
            throw new BusinessException(ErrorCode.CHAT_RUNTIME_ALREADY_RUNNING);
        }

        String streamId = event.getStreamId() == null || event.getStreamId().isBlank() ? UUID.randomUUID().toString() : event.getStreamId();
        String requestId = event.getRequestId() == null || event.getRequestId().isBlank() ? RequestIdHolder.get() : event.getRequestId();
        String content = payload.path("content").asText("").trim();
        if (content.isBlank()) {
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_EMPTY);
        }

        if (RequestIdHolder.get() == null) {
            RequestIdHolder.set(requestId);
        }
        ActiveExecution execution = new ActiveExecution(streamId, requestId);
        activeExecutionRegistry.register(sessionId, execution);

        Long userMessageId = persistUserMessage(sessionId, content, requestId);
        updateSessionForRun(sessionId, streamId, requestId);
        chatRuntimeStateStore.writeRuntimeState(sessionId, RuntimeState.builder()
                .sessionId(sessionId)
                .userId(userId)
                .spaceId(session.getSpaceId())
                .sessionKind(session.getSessionKind())
                .runtimeStatus(ChatRuntimeStatus.RUNNING)
                .requestId(requestId)
                .streamId(streamId)
                .lastAckSeq(0L)
                .build());
        chatRuntimeStateStore.writeShortTermContext(sessionId, ShortTermContext.builder()
                .recentMessages(List.of("USER: " + content))
                .evidenceTitles(List.of())
                .build());

        ServerEventEnvelope started = chatRuntimeStateStore.appendEvent(sessionId, ServerEventEnvelope.builder()
                .event("chat.started")
                .requestId(requestId)
                .streamId(streamId)
                .sessionId(sessionId)
                .messageId(userMessageId)
                .payload(objectNode(Map.of("runtimeStatus", ChatRuntimeStatus.RUNNING.name())))
                .build());
        send(socketSession, started);

        try {
            List<EvidenceItem> evidenceItems = loadEvidence(userId, session, content);
            List<String> evidenceTitles = evidenceItems.stream().map(EvidenceItem::documentTitle).distinct().toList();
            chatRuntimeStateStore.writeShortTermContext(sessionId, ShortTermContext.builder()
                    .recentMessages(List.of("USER: " + content))
                    .evidenceTitles(evidenceTitles)
                    .build());

            String answer = buildAnswer(sessionId, content, evidenceItems);
            streamAnswer(sessionId, socketSession, execution, requestId, streamId, answer);

            if (execution.getStopRequested().get()) {
                return;
            }

            Long assistantMessageId = persistAssistantMessage(sessionId, answer, requestId, evidenceItems);
            List<CitationResponse> citations = evidenceItems.isEmpty()
                    ? List.of()
                    : citationService.listByMessage(userId, assistantMessageId, session.getSpaceId());
            updateSessionRuntimeStatus(sessionId, ChatRuntimeStatus.IDLE);
            chatRuntimeStateStore.writeRuntimeState(sessionId, RuntimeState.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .spaceId(session.getSpaceId())
                    .sessionKind(session.getSessionKind())
                    .runtimeStatus(ChatRuntimeStatus.IDLE)
                    .requestId(requestId)
                    .streamId(streamId)
                    .lastAckSeq(0L)
                    .build());
            chatRuntimeStateStore.writeStreamState(sessionId, StreamState.builder()
                    .streamId(streamId)
                    .status(ChatRuntimeStatus.IDLE)
                    .partialContent(answer)
                    .build());
            ServerEventEnvelope completed = chatRuntimeStateStore.appendEvent(sessionId, ServerEventEnvelope.builder()
                    .event("chat.completed")
                    .requestId(requestId)
                    .streamId(streamId)
                    .sessionId(sessionId)
                    .messageId(assistantMessageId)
                    .payload(objectNode(Map.of(
                            "assistantMessageId", assistantMessageId,
                            "answer", answer,
                            "citations", citations
                    )))
                    .build());
            send(socketSession, completed);
        } catch (Exception ex) {
            log.warn("Chat runtime failed", ex);
            updateSessionRuntimeStatus(sessionId, ChatRuntimeStatus.FAILED);
            ServerEventEnvelope failed = chatRuntimeStateStore.appendEvent(sessionId, ServerEventEnvelope.builder()
                    .event("chat.failed")
                    .requestId(requestId)
                    .streamId(streamId)
                    .sessionId(sessionId)
                    .error(ServerEventEnvelope.ErrorPayload.builder()
                            .code(ex instanceof BusinessException be ? be.getErrorCode().name() : ErrorCode.CHAT_STREAM_FAILED.name())
                            .message(ex.getMessage())
                            .build())
                    .build());
            send(socketSession, failed);
            throw ex instanceof RuntimeException runtimeException ? runtimeException : new BusinessException(ErrorCode.CHAT_STREAM_FAILED);
        } finally {
            activeExecutionRegistry.remove(sessionId);
            RequestIdHolder.clear();
        }
    }

    private void streamAnswer(
            Long sessionId,
            WebSocketSession socketSession,
            ActiveExecution execution,
            String requestId,
            String streamId,
            String answer
    ) {
        int chunkSize = Math.max(1, chatRuntimeProperties.deltaChunkSize());
        StringBuilder partial = new StringBuilder();
        for (int index = 0; index < answer.length(); index += chunkSize) {
            if (execution.getStopRequested().get()) {
                break;
            }
            String delta = answer.substring(index, Math.min(answer.length(), index + chunkSize));
            partial.append(delta);
            chatRuntimeStateStore.writeStreamState(sessionId, StreamState.builder()
                    .streamId(streamId)
                    .status(ChatRuntimeStatus.RUNNING)
                    .partialContent(partial.toString())
                    .build());
            ServerEventEnvelope deltaEvent = chatRuntimeStateStore.appendEvent(sessionId, ServerEventEnvelope.builder()
                    .event("chat.delta")
                    .requestId(requestId)
                    .streamId(streamId)
                    .sessionId(sessionId)
                    .payload(objectNode(Map.of(
                            "delta", delta,
                            "partialContent", partial.toString()
                    )))
                    .build());
            send(socketSession, deltaEvent);
            sleep(chatRuntimeProperties.deltaDelayMs());
        }
    }

    private List<EvidenceItem> loadEvidence(Long userId, ChatSession session, String question) {
        contextReadRouter.resolve(session.getSessionKind(), session.getSessionType());
        return evidencePostProcessor.process(
                bm25Retriever.retrieve(new TeamRetrievalQuery(
                        userId,
                        session.getSpaceId(),
                        resolveKnowledgeBaseScopeIds(session),
                        question,
                        ragProperties.retrieval().topK()
                )),
                EvidenceOptions.builder()
                        .maxEvidencePerDocument(ragProperties.retrieval().perDocumentLimit())
                        .mergeAdjacentChunks(true)
                        .maxMergedChars(ragProperties.retrieval().maxMergedChars())
                        .finalTopK(ragProperties.retrieval().topK())
                        .maxContextChars(ragProperties.retrieval().contextMaxChars())
                        .build()
        );
    }

    private String buildAnswer(Long sessionId, String question, List<EvidenceItem> evidenceItems) {
        if (evidenceItems.isEmpty()) {
            return ragProperties.prompt().noResultText() + "。当前资料不足，无法给出可靠引用。";
        }
        PromptMessages prompt = teamRagPromptBuilder.build(question, evidenceItems, recentMessages(sessionId));
        LlmResponse response = llmClient.chat(
                prompt.messages().stream().map(message -> new LlmMessage(message.role(), message.content())).toList(),
                LlmOptions.builder().temperature(0.3d).maxTokens(2000).build()
        );
        return response.content();
    }

    private Long persistUserMessage(Long sessionId, String content, String requestId) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(status -> {
            ChatMessage message = createMessage(sessionId, ChatMessageRole.USER, content, requestId, ChatMessageStatus.COMPLETED);
            return chatMessageRepository.save(message).getId();
        });
    }

    private Long persistAssistantMessage(Long sessionId, String content, String requestId, List<EvidenceItem> evidenceItems) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(status -> {
            ChatMessage message = createMessage(sessionId, ChatMessageRole.ASSISTANT, content, requestId, ChatMessageStatus.COMPLETED);
            ChatMessage saved = chatMessageRepository.save(message);
            if (!evidenceItems.isEmpty()) {
                citationService.saveForAssistantMessage(saved.getId(), getRequiredSession(sessionId).getSpaceId(), evidenceItems);
            }
            return saved.getId();
        });
    }

    private ChatMessage createMessage(Long sessionId, ChatMessageRole role, String content, String requestId, ChatMessageStatus status) {
        int nextSeq = chatMessageRepository.findTopBySessionIdOrderByMessageSeqDesc(sessionId)
                .map(existing -> existing.getMessageSeq() + 1)
                .orElse(1);
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setMessageSeq(nextSeq);
        message.setRole(role);
        message.setContent(content);
        message.setMessageType(ChatMessageType.TEXT);
        message.setStatus(status);
        message.setRequestId(requestId);
        return message;
    }

    private void updateSessionForRun(Long sessionId, String streamId, String requestId) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            ChatSession session = chatSessionRepository.findByIdForUpdate(sessionId).orElseThrow();
            session.setRuntimeStatus(ChatRuntimeStatus.RUNNING);
            session.setLastActiveAt(LocalDateTime.now());
            session.setLatestContextSnapshotJson(stringify(Map.of(
                    "streamId", streamId,
                    "requestId", requestId
            )));
            chatSessionRepository.save(session);
        });
    }

    private void updateSessionRuntimeStatus(Long sessionId, ChatRuntimeStatus runtimeStatus) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            ChatSession session = chatSessionRepository.findByIdForUpdate(sessionId).orElseThrow();
            session.setRuntimeStatus(runtimeStatus);
            session.setLastActiveAt(LocalDateTime.now());
            chatSessionRepository.save(session);
        });
    }

    private ChatSession getRequiredSession(Long sessionId) {
        return chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_SESSION_NOT_FOUND));
    }

    private List<Long> resolveKnowledgeBaseScopeIds(ChatSession session) {
        if (session.getScopeType() == com.noteweave.chat.model.ChatScopeType.SPACE) {
            return knowledgeBaseRepository.findBySpaceIdAndStatus(session.getSpaceId(), KnowledgeBaseStatus.ACTIVE).stream()
                    .map(com.noteweave.team.kb.model.KnowledgeBase::getId)
                    .toList();
        }
        List<Long> scopeIds = chatSessionScopeRepository.findBySessionIdOrderByIdAsc(session.getId()).stream()
                .map(com.noteweave.chat.model.ChatSessionScope::getScopeId)
                .toList();
        return knowledgeBaseRepository.findBySpaceIdAndStatusAndIdIn(
                        session.getSpaceId(),
                        KnowledgeBaseStatus.ACTIVE,
                        scopeIds)
                .stream()
                .map(com.noteweave.team.kb.model.KnowledgeBase::getId)
                .toList();
    }

    private List<ChatMessage> recentMessages(Long sessionId) {
        List<ChatMessage> all = chatMessageRepository.findBySessionIdOrderByMessageSeqAsc(sessionId);
        return all.size() <= 4 ? all : all.subList(Math.max(0, all.size() - 4), all.size());
    }

    private String readPartialContent(Long sessionId) {
        RuntimeSnapshot snapshot = chatRuntimeStateStore.readSnapshot(sessionId).orElse(null);
        if (snapshot == null || snapshot.streamState() == null || snapshot.streamState().getPartialContent() == null) {
            return "";
        }
        return snapshot.streamState().getPartialContent();
    }

    private void send(WebSocketSession session, ServerEventEnvelope envelope) {
        try {
            if (session == null || !session.isOpen()) {
                return;
            }
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
        } catch (Exception ex) {
            log.debug("Failed to send websocket event {}", envelope.getEvent(), ex);
        }
    }

    private JsonNode objectNode(Map<String, ?> value) {
        return objectMapper.valueToTree(value);
    }

    private String stringify(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private void sleep(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
