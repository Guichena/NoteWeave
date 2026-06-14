package com.noteweave.chat.runtime.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.dto.RetrievalTraceCreateRequest;
import com.noteweave.chat.dto.RetrievalTraceItemCreateRequest;
import com.noteweave.chat.model.ChatDraftStatus;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatMessageRole;
import com.noteweave.chat.model.ChatMessageStatus;
import com.noteweave.chat.model.ChatMessageType;
import com.noteweave.chat.model.ChatRuntimeStatus;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.model.ChatSessionKind;
import com.noteweave.chat.model.ChatSessionStatus;
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
import com.noteweave.llm.dto.LlmCallContext;
import com.noteweave.llm.dto.LlmMessage;
import com.noteweave.llm.dto.LlmOptions;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.service.ObservedLlmGateway;
import com.noteweave.memory.service.MemoryContextService;
import com.noteweave.memory.service.MemoryWritebackService;
import com.noteweave.memory.service.PromptMemoryContext;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.prompt.model.PromptVersion;
import com.noteweave.prompt.service.PromptTemplateRenderer;
import com.noteweave.prompt.service.PromptVersionService;
import com.noteweave.studio.service.StudioMcpChatTriggerService;
import com.noteweave.team.kb.model.KnowledgeBaseStatus;
import com.noteweave.team.kb.repository.KnowledgeBaseRepository;
import com.noteweave.team.rag.config.RagProperties;
import com.noteweave.team.rag.evidence.EvidenceItem;
import com.noteweave.team.rag.evidence.EvidenceOptions;
import com.noteweave.team.rag.evidence.EvidencePostProcessor;
import com.noteweave.team.rag.prompt.PromptMessages;
import com.noteweave.team.rag.prompt.TeamRagPromptBuilder;
import com.noteweave.team.rag.retriever.HybridRetriever;
import com.noteweave.team.rag.retriever.RetrievalHit;
import com.noteweave.team.rag.retriever.TeamRetrievalQuery;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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

    private static final String NO_RESULT_SUFFIX = "。当前证据不足，无法给出可靠引用。";

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionScopeRepository chatSessionScopeRepository;
    private final ResourceAccessService resourceAccessService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final HybridRetriever hybridRetriever;
    private final EvidencePostProcessor evidencePostProcessor;
    private final TeamRagPromptBuilder teamRagPromptBuilder;
    private final ObservedLlmGateway observedLlmGateway;
    private final CitationService citationService;
    private final com.noteweave.chat.service.RetrievalTraceService retrievalTraceService;
    private final ActiveExecutionRegistry activeExecutionRegistry;
    private final ChatRuntimeStateStore chatRuntimeStateStore;
    private final ContextReadRouter contextReadRouter;
    private final MemoryContextService memoryContextService;
    private final MemoryWritebackService memoryWritebackService;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    private final ChatRuntimeProperties chatRuntimeProperties;
    private final PlatformTransactionManager transactionManager;
    private final Executor applicationTaskExecutor;
    private final PromptVersionService promptVersionService;
    private final PromptTemplateRenderer promptTemplateRenderer;
    private final StudioMcpChatTriggerService studioMcpChatTriggerService;

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
        ChatSession session = getRequiredActiveSession(event.getSessionId());
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
        ChatSession session = getRequiredActiveSession(event.getSessionId());
        resourceAccessService.requireAskQuestion(userId, session.getSpaceId());
        ActiveExecution execution = activeExecutionRegistry.get(session.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_RUNTIME_NOT_FOUND));
        execution.stop();
        updateSessionRuntimeStatus(session.getId(), ChatRuntimeStatus.STOPPED);
        chatRuntimeStateStore.writeRuntimeState(session.getId(), RuntimeState.builder()
                .sessionId(session.getId())
                .userId(userId)
                .spaceId(session.getSpaceId())
                .sessionKind(session.getSessionKind())
                .runtimeStatus(ChatRuntimeStatus.STOPPED)
                .requestId(event.getRequestId())
                .streamId(execution.getStreamId())
                .build());
        chatRuntimeStateStore.writeStreamState(session.getId(), StreamState.builder()
                .streamId(execution.getStreamId())
                .status(ChatRuntimeStatus.STOPPED)
                .partialContent(readPartialContent(session.getId()))
                .build());
        emitSessionStateUpdated(socketSession, session, event.getRequestId(), execution.getStreamId(), ChatRuntimeStatus.STOPPED);
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
        Long sessionId = event.getSessionId();
        String streamId = event.getStreamId() == null || event.getStreamId().isBlank()
                ? UUID.randomUUID().toString()
                : event.getStreamId();
        String requestId = event.getRequestId() == null || event.getRequestId().isBlank()
                ? RequestIdHolder.get()
                : event.getRequestId();
        ActiveExecution execution = new ActiveExecution(streamId, requestId);
        boolean executionRegistered = false;
        boolean runtimeStarted = false;
        ChatSession session = null;

        try {
            JsonNode payload = event.getPayload();
            session = getRequiredActiveSession(sessionId);
            resourceAccessService.requireAskQuestion(userId, session.getSpaceId());
            if (session.getSessionType() != ChatSessionType.TEAM_CHAT) {
                throw new BusinessException(ErrorCode.CHAT_SESSION_TYPE_UNSUPPORTED);
            }
            ensureWritableSession(session);

            String content = payload.path("content").asText("").trim();
            if (content.isBlank()) {
                throw new BusinessException(ErrorCode.CHAT_MESSAGE_EMPTY);
            }
            ContextReadPlan readPlan = contextReadRouter.resolve(session.getSessionKind(), session.getSessionType());
            PromptMemoryContext memoryContext = memoryContextService.load(userId, session, readPlan, content);

            if (RequestIdHolder.get() == null) {
                RequestIdHolder.set(requestId);
            }
            if (!activeExecutionRegistry.registerIfAbsent(sessionId, execution)) {
                throw new BusinessException(ErrorCode.CHAT_RUNTIME_ALREADY_RUNNING);
            }
            executionRegistered = true;

            ChatMessage userMessage = persistUserMessage(sessionId, content, requestId);
            updateSessionForRun(sessionId, streamId, requestId);
            runtimeStarted = true;
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
            emitSessionStateUpdated(socketSession, session, requestId, streamId, ChatRuntimeStatus.RUNNING);

            ServerEventEnvelope started = chatRuntimeStateStore.appendEvent(sessionId, ServerEventEnvelope.builder()
                    .event("chat.started")
                    .requestId(requestId)
                    .streamId(streamId)
                    .sessionId(sessionId)
                    .messageId(userMessage.getId())
                    .payload(objectNode(Map.of("runtimeStatus", ChatRuntimeStatus.RUNNING.name())))
                    .build());
            send(socketSession, started);

            var toolTrigger = studioMcpChatTriggerService.maybeTrigger(userId, session, userMessage.getId(), content);
            if (toolTrigger.isPresent()) {
                StudioMcpChatTriggerService.ToolTriggerResult trigger = toolTrigger.get();
                ChatMessage assistantMessage = null;
                if (session.getSessionKind() == ChatSessionKind.FORMAL) {
                    assistantMessage = persistAssistantMessage(sessionId, trigger.answer(), requestId, trigger.task().artifactId());
                    memoryWritebackService.writeAfterRound(session, userMessage, assistantMessage, List.of());
                }
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
                        .partialContent(trigger.answer())
                        .build());
                emitSessionStateUpdated(socketSession, session, requestId, streamId, ChatRuntimeStatus.IDLE);

                Map<String, Object> completedPayload = new LinkedHashMap<>();
                completedPayload.put("answer", trigger.answer());
                completedPayload.put("citations", List.of());
                completedPayload.put("persisted", session.getSessionKind() == ChatSessionKind.FORMAL);
                completedPayload.put("assistantMessageId", assistantMessage == null ? null : assistantMessage.getId());
                completedPayload.put("artifactId", trigger.task().artifactId());
                completedPayload.put("artifactSpaceId", trigger.task().artifactSpaceId());
                completedPayload.put("taskId", trigger.task().taskId());
                completedPayload.put("toolName", trigger.toolName());
                ServerEventEnvelope completed = chatRuntimeStateStore.appendEvent(sessionId, ServerEventEnvelope.builder()
                        .event("chat.completed")
                        .requestId(requestId)
                        .streamId(streamId)
                        .sessionId(sessionId)
                        .messageId(assistantMessage == null ? null : assistantMessage.getId())
                        .payload(objectNode(completedPayload))
                        .build());
                send(socketSession, completed);
                return;
            }

            LoadedEvidence loadedEvidence = loadEvidence(userId, session, userMessage.getId(), content, readPlan);
            List<EvidenceItem> evidenceItems = loadedEvidence.evidenceItems();
            List<String> evidenceTitles = evidenceItems.stream().map(EvidenceItem::documentTitle).distinct().toList();
            chatRuntimeStateStore.writeShortTermContext(sessionId, ShortTermContext.builder()
                    .recentMessages(List.of("USER: " + content))
                    .evidenceTitles(evidenceTitles)
                    .build());

            AnswerBuildResult answerBuildResult = buildAnswer(userId, session, userMessage.getId(), content, evidenceItems, readPlan, memoryContext);
            String answer = answerBuildResult.answer();
            streamAnswer(sessionId, socketSession, execution, requestId, streamId, answer);

            if (execution.getStopRequested().get()) {
                return;
            }

            ChatMessage assistantMessage = null;
            List<CitationResponse> citations = List.of();
            if (session.getSessionKind() == ChatSessionKind.FORMAL) {
                assistantMessage = persistAssistantMessage(sessionId, answer, requestId);
                citations = evidenceItems.isEmpty()
                        ? List.of()
                        : citationService.saveForAssistantMessage(
                                assistantMessage.getId(),
                                session.getSpaceId(),
                                evidenceItems,
                                loadedEvidence.retrievalTraceId()
                        );
                memoryWritebackService.writeAfterRound(session, userMessage, assistantMessage, citations);
            }

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
            emitSessionStateUpdated(socketSession, session, requestId, streamId, ChatRuntimeStatus.IDLE);

            Map<String, Object> completedPayload = new LinkedHashMap<>();
            completedPayload.put("answer", answer);
            completedPayload.put("citations", citations);
            completedPayload.put("persisted", session.getSessionKind() == ChatSessionKind.FORMAL);
            completedPayload.put("assistantMessageId", assistantMessage == null ? null : assistantMessage.getId());

            ServerEventEnvelope completed = chatRuntimeStateStore.appendEvent(sessionId, ServerEventEnvelope.builder()
                    .event("chat.completed")
                    .requestId(requestId)
                    .streamId(streamId)
                    .sessionId(sessionId)
                    .messageId(assistantMessage == null ? null : assistantMessage.getId())
                    .payload(objectNode(completedPayload))
                    .build());
            send(socketSession, completed);
        } catch (Exception ex) {
            log.warn("Chat runtime failed", ex);
            if (runtimeStarted && session != null) {
                updateSessionRuntimeStatus(sessionId, ChatRuntimeStatus.FAILED);
                chatRuntimeStateStore.writeRuntimeState(sessionId, RuntimeState.builder()
                        .sessionId(sessionId)
                        .userId(userId)
                        .spaceId(session.getSpaceId())
                        .sessionKind(session.getSessionKind())
                        .runtimeStatus(ChatRuntimeStatus.FAILED)
                        .requestId(requestId)
                        .streamId(streamId)
                        .build());
                chatRuntimeStateStore.writeStreamState(sessionId, StreamState.builder()
                        .streamId(streamId)
                        .status(ChatRuntimeStatus.FAILED)
                        .partialContent(readPartialContent(sessionId))
                        .build());
                emitSessionStateUpdated(socketSession, session, requestId, streamId, ChatRuntimeStatus.FAILED);
            }

            ServerEventEnvelope failed = ServerEventEnvelope.builder()
                    .event("chat.failed")
                    .requestId(requestId)
                    .streamId(streamId)
                    .sessionId(sessionId)
                    .error(ServerEventEnvelope.ErrorPayload.builder()
                            .code(ex instanceof BusinessException businessException
                                    ? businessException.getErrorCode().name()
                                    : ErrorCode.CHAT_STREAM_FAILED.name())
                            .message(ex.getMessage())
                            .build())
                    .build();
            if (runtimeStarted || session != null) {
                failed = chatRuntimeStateStore.appendEvent(sessionId, failed);
            }
            send(socketSession, failed);
            return;
        } finally {
            if (executionRegistered) {
                activeExecutionRegistry.remove(sessionId);
            }
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

    private LoadedEvidence loadEvidence(Long userId, ChatSession session, Long messageId, String question, ContextReadPlan readPlan) {
        if (!readPlan.readRetrievalEvidence()) {
            return new LoadedEvidence(List.of(), null);
        }
        Instant retrievalStart = Instant.now();
        HybridRetriever.HybridRetrievalResult retrieval = hybridRetriever.retrieve(
                new TeamRetrievalQuery(
                        userId,
                        session.getSpaceId(),
                        resolveKnowledgeBaseScopeIds(session),
                        question,
                        ragProperties.retrieval().topK(),
                        true,
                        session.getResearchQuestionId()
                ),
                ragProperties.retrieval().mode()
        );
        List<EvidenceItem> evidenceItems = evidencePostProcessor.process(
                retrieval.fusedHits().stream()
                        .map(this::toRetrievedChunk)
                        .toList(),
                EvidenceOptions.builder()
                        .maxEvidencePerDocument(ragProperties.retrieval().perDocumentLimit())
                        .mergeAdjacentChunks(true)
                        .maxMergedChars(ragProperties.retrieval().maxMergedChars())
                        .finalTopK(ragProperties.retrieval().topK())
                        .maxContextChars(ragProperties.retrieval().contextMaxChars())
                        .minScore(ragProperties.retrieval().minScore())
                        .build()
        );
        long latencyMs = Math.max(1L, Duration.between(retrievalStart, Instant.now()).toMillis());
        Long traceId = retrievalTraceService.createTrace(RetrievalTraceCreateRequest.builder()
                .userId(userId)
                .spaceId(session.getSpaceId())
                .sessionId(session.getId())
                .messageId(messageId)
                .scene("WORKSPACE_CHAT")
                .queryText(question)
                .retrieverType("HYBRID")
                .topK(ragProperties.retrieval().topK())
                .latencyMs(latencyMs)
                .retrievedChunkCount(retrieval.fusedHits().size())
                .retrievalMode(retrieval.retrievalMode().name())
                .bm25Count(retrieval.bm25Count())
                .vectorCount(retrieval.vectorCount())
                .fusionCount(retrieval.fusionCount())
                .fallbackUsed(retrieval.fallbackUsed())
                .traceJson(retrieval.traceJson())
                .build());
        retrievalTraceService.addItems(traceId, buildTraceItems(retrieval.fusedHits(), evidenceItems));
        return new LoadedEvidence(evidenceItems, traceId);
    }

    private com.noteweave.team.rag.retriever.RetrievedChunk toRetrievedChunk(RetrievalHit hit) {
        Integer indexVersion = hit.metadata() == null ? null : (Integer) hit.metadata().get("indexVersion");
        return new com.noteweave.team.rag.retriever.RetrievedChunk(
                hit.chunkId(),
                hit.documentId(),
                hit.knowledgeBaseId(),
                hit.spaceId(),
                hit.metadata() == null ? "DOCUMENT" : String.valueOf(hit.metadata().getOrDefault("sourceType", "DOCUMENT")),
                hit.metadata() == null ? hit.documentId() : longValue(hit.metadata().getOrDefault("sourceId", hit.documentId())),
                indexVersion,
                hit.chunkIndex(),
                hit.documentTitle(),
                hit.content(),
                hit.score(),
                1,
                null,
                null,
                indexVersion == null ? "unknown" : String.valueOf(indexVersion)
        );
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private List<RetrievalTraceItemCreateRequest> buildTraceItems(List<RetrievalHit> hits, List<EvidenceItem> evidenceItems) {
        return java.util.stream.IntStream.range(0, hits.size())
                .mapToObj(index -> {
                    RetrievalHit hit = hits.get(index);
                    boolean selected = evidenceItems.stream()
                            .anyMatch(item -> item.sources().stream().anyMatch(source -> source.chunkId().equals(hit.chunkId())));
                    return RetrievalTraceItemCreateRequest.builder()
                            .sourceType(hit.metadata() == null ? "DOCUMENT_CHUNK" : String.valueOf(hit.metadata().getOrDefault("sourceType", "DOCUMENT_CHUNK")))
                            .sourceId(hit.metadata() == null ? hit.documentId() : longValue(hit.metadata().get("sourceId")))
                            .documentId(hit.documentId())
                            .chunkId(hit.chunkId())
                            .wikiPageId(null)
                            .score(hit.score())
                            .rank(index + 1)
                            .selectedAsEvidence(selected)
                            .metadataJson(stringify(hit.metadata()))
                            .build();
                })
                .toList();
    }

    private String renderPrompt(PromptVersion promptVersion, Map<String, Object> variables) {
        if (promptVersion == null) {
            return null;
        }
        return promptTemplateRenderer.render(promptVersion.getContent(), variables);
    }

    private AnswerBuildResult buildAnswer(
            Long userId,
            ChatSession session,
            Long messageId,
            String question,
            List<EvidenceItem> evidenceItems,
            ContextReadPlan readPlan,
            PromptMemoryContext memoryContext
    ) {
        if (evidenceItems.isEmpty()) {
            return new AnswerBuildResult(ragProperties.prompt().noResultText() + NO_RESULT_SUFFIX, null);
        }
        PromptVersion activePrompt = promptVersionService.findActiveEntity("WORKSPACE_CHAT").orElse(null);
        String systemPrompt = renderPrompt(activePrompt, Map.of("noResultText", ragProperties.prompt().noResultText()));
        PromptMessages prompt = teamRagPromptBuilder.build(
                question,
                evidenceItems,
                readPlan.readRecentHistory() ? recentMessages(session.getId()) : List.of(),
                memoryContext,
                systemPrompt
        );
        ObservedLlmGateway.ObservedLlmResult observed = observedLlmGateway.chat(
                LlmCallContext.builder()
                        .userId(userId)
                        .spaceId(session.getSpaceId())
                        .sessionId(session.getId())
                        .messageId(messageId)
                        .scene("WORKSPACE_CHAT")
                        .promptVersionId(activePrompt == null ? null : activePrompt.getId())
                        .messages(prompt.messages().stream().map(message -> new LlmMessage(message.role(), message.content())).toList())
                        .build(),
                LlmOptions.builder().temperature(0.3d).maxTokens(2000).build()
        );
        LlmResponse response = observed.response();
        return new AnswerBuildResult(response.content(), observed.logId());
    }

    private ChatMessage persistUserMessage(Long sessionId, String content, String requestId) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(status -> {
            ChatMessage message = createMessage(sessionId, ChatMessageRole.USER, content, requestId, ChatMessageStatus.COMPLETED);
            return chatMessageRepository.save(message);
        });
    }

    private ChatMessage persistAssistantMessage(Long sessionId, String content, String requestId) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(status -> {
            ChatMessage message = createMessage(sessionId, ChatMessageRole.ASSISTANT, content, requestId, ChatMessageStatus.COMPLETED);
            return chatMessageRepository.save(message);
        });
    }

    private ChatMessage persistAssistantMessage(Long sessionId, String content, String requestId, Long artifactId) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(status -> {
            ChatMessage message = createMessage(sessionId, ChatMessageRole.ASSISTANT, content, requestId, ChatMessageStatus.COMPLETED);
            message.setArtifactId(artifactId);
            return chatMessageRepository.save(message);
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

    private ChatSession getRequiredActiveSession(Long sessionId) {
        return chatSessionRepository.findByIdAndStatus(sessionId, ChatSessionStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_SESSION_NOT_FOUND));
    }

    private void ensureWritableSession(ChatSession session) {
        if (session.getSessionKind() == ChatSessionKind.DRAFT
                && session.getDraftStatus() != ChatDraftStatus.DRAFT_ACTIVE) {
            throw new BusinessException(ErrorCode.CHAT_DRAFT_INVALID_STATE);
        }
    }

    private void emitSessionStateUpdated(
            WebSocketSession socketSession,
            ChatSession session,
            String requestId,
            String streamId,
            ChatRuntimeStatus runtimeStatus
    ) {
        ServerEventEnvelope envelope = chatRuntimeStateStore.appendEvent(session.getId(), ServerEventEnvelope.builder()
                .event("session.state.updated")
                .requestId(requestId)
                .streamId(streamId)
                .sessionId(session.getId())
                .payload(objectNode(Map.of(
                        "runtimeStatus", runtimeStatus.name(),
                        "sessionKind", session.getSessionKind().name(),
                        "draftStatus", session.getDraftStatus() == null ? "" : session.getDraftStatus().name()
                )))
                .build());
        send(socketSession, envelope);
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

    private record LoadedEvidence(List<EvidenceItem> evidenceItems, Long retrievalTraceId) {
    }

    private record AnswerBuildResult(String answer, Long llmCallLogId) {
    }
}
