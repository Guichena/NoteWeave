package com.noteweave.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.dto.RetrievalTraceCreateRequest;
import com.noteweave.chat.dto.RetrievalTraceItemCreateRequest;
import com.noteweave.chat.dto.TeamAskRequest;
import com.noteweave.chat.dto.TeamAskResponse;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatMessageRole;
import com.noteweave.chat.model.ChatMessageStatus;
import com.noteweave.chat.model.ChatMessageType;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.model.ChatSessionKind;
import com.noteweave.chat.repository.ChatMessageRepository;
import com.noteweave.chat.runtime.service.ContextReadPlan;
import com.noteweave.chat.runtime.service.ContextReadRouter;
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
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class TeamChatService {

    private final ChatSessionService chatSessionService;
    private final ChatMessageRepository chatMessageRepository;
    private final ResourceAccessService resourceAccessService;
    private final HybridRetriever hybridRetriever;
    private final EvidencePostProcessor evidencePostProcessor;
    private final TeamRagPromptBuilder teamRagPromptBuilder;
    private final ObservedLlmGateway observedLlmGateway;
    private final CitationService citationService;
    private final RetrievalTraceService retrievalTraceService;
    private final RagProperties ragProperties;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ContextReadRouter contextReadRouter;
    private final MemoryContextService memoryContextService;
    private final MemoryWritebackService memoryWritebackService;
    private final PromptVersionService promptVersionService;
    private final PromptTemplateRenderer promptTemplateRenderer;
    private final StudioMcpChatTriggerService studioMcpChatTriggerService;

    public TeamAskResponse ask(Long userId, Long sessionId, TeamAskRequest request) {
        ChatSession session = chatSessionService.getRequiredActiveSession(sessionId);
        if (session.getSessionType() != com.noteweave.chat.model.ChatSessionType.TEAM_CHAT) {
            throw new BusinessException(ErrorCode.CHAT_SESSION_TYPE_UNSUPPORTED);
        }
        if (session.getSessionKind() != ChatSessionKind.FORMAL) {
            throw new BusinessException(ErrorCode.CHAT_DRAFT_INVALID_STATE, "Draft sessions must use the websocket runtime");
        }
        resourceAccessService.requireAskQuestion(userId, session.getSpaceId());

        String question = request.getContent().trim();
        ChatMessage userMessage = persistMessage(sessionId, ChatMessageRole.USER, question, null, null);
        var toolTrigger = studioMcpChatTriggerService.maybeTrigger(userId, session, userMessage.getId(), question);
        if (toolTrigger.isPresent()) {
            StudioMcpChatTriggerService.ToolTriggerResult trigger = toolTrigger.get();
            AssistantOutcome outcome = persistAssistantOutcome(
                    session,
                    userMessage,
                    null,
                    trigger.answer(),
                    writeJson(Map.of(
                            "mcpTool", trigger.toolName(),
                            "taskId", trigger.task().taskId(),
                            "artifactId", trigger.task().artifactId(),
                            "artifactSpaceId", trigger.task().artifactSpaceId()
                    )),
                    List.of(),
                    trigger.task().artifactId(),
                    trigger.task().artifactSpaceId(),
                    trigger.task().taskId(),
                    trigger.toolName()
            );
            memoryWritebackService.writeAfterRound(session, userMessage, outcome.assistantMessage(), outcome.response().getCitations());
            return outcome.response();
        }
        ContextReadPlan readPlan = contextReadRouter.resolve(session.getSessionKind(), session.getSessionType());

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
                retrieval.fusedHits().stream().map(this::toRetrievedChunk).toList(),
                EvidenceOptions.builder()
                        .maxEvidencePerDocument(ragProperties.retrieval().perDocumentLimit())
                        .mergeAdjacentChunks(true)
                        .maxMergedChars(ragProperties.retrieval().maxMergedChars())
                        .finalTopK(ragProperties.retrieval().topK())
                        .maxContextChars(ragProperties.retrieval().contextMaxChars())
                        .minScore(ragProperties.retrieval().minScore())
                        .build()
        );
        long retrievalLatency = Math.max(1L, Duration.between(retrievalStart, Instant.now()).toMillis());
        Long retrievalTraceId = persistRetrievalTrace(userId, session, userMessage.getId(), question, retrievalLatency, retrieval, evidenceItems);

        if (evidenceItems.isEmpty()) {
            AssistantOutcome outcome = persistAssistantOutcome(
                    session,
                    userMessage,
                    retrievalTraceId,
                    ragProperties.prompt().noResultText() + "。当前证据不足，无法给出可靠引用。",
                    writeJson(Map.of("retrievalEmpty", true)),
                    List.of(),
                    null,
                    null,
                    null,
                    null
            );
            memoryWritebackService.writeAfterRound(session, userMessage, outcome.assistantMessage(), outcome.response().getCitations());
            return outcome.response();
        }

        PromptMemoryContext memoryContext = memoryContextService.load(userId, session, readPlan, question);
        PromptVersion activePrompt = promptVersionService.findActiveEntity("TEAM_RAG_CHAT").orElse(null);
        String systemPrompt = renderPrompt(activePrompt, Map.of("noResultText", ragProperties.prompt().noResultText()));
        PromptMessages prompt = teamRagPromptBuilder.build(question, evidenceItems, recentMessages(sessionId), memoryContext, systemPrompt);
        ObservedLlmGateway.ObservedLlmResult observed = observedLlmGateway.chat(
                LlmCallContext.builder()
                        .userId(userId)
                        .spaceId(session.getSpaceId())
                        .sessionId(session.getId())
                        .messageId(userMessage.getId())
                        .scene("TEAM_RAG_CHAT")
                        .promptVersionId(activePrompt == null ? null : activePrompt.getId())
                        .messages(prompt.messages().stream().map(message -> new LlmMessage(message.role(), message.content())).toList())
                        .build(),
                LlmOptions.builder().temperature(0.3d).maxTokens(2000).build()
        );
        LlmResponse llmResponse = observed.response();
        AssistantOutcome outcome = persistAssistantOutcome(
                session,
                userMessage,
                retrievalTraceId,
                llmResponse.content(),
                writeJson(Map.of(
                        "provider", llmResponse.provider(),
                        "model", llmResponse.model(),
                        "inputTokens", llmResponse.inputTokens(),
                        "outputTokens", llmResponse.outputTokens()
                )),
                evidenceItems,
                null,
                null,
                null,
                null
        );
        memoryWritebackService.writeAfterRound(session, userMessage, outcome.assistantMessage(), outcome.response().getCitations());
        return outcome.response();
    }

    private List<Long> resolveKnowledgeBaseScopeIds(ChatSession session) {
        if (session.getScopeType() == com.noteweave.chat.model.ChatScopeType.SPACE) {
            return knowledgeBaseRepository.findBySpaceIdAndStatus(session.getSpaceId(), KnowledgeBaseStatus.ACTIVE).stream()
                    .map(com.noteweave.team.kb.model.KnowledgeBase::getId)
                    .toList();
        }
        List<Long> scopedKnowledgeBaseIds = chatSessionService.listScopeIds(session.getId());
        if (scopedKnowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        return knowledgeBaseRepository.findBySpaceIdAndStatusAndIdIn(session.getSpaceId(), KnowledgeBaseStatus.ACTIVE, scopedKnowledgeBaseIds).stream()
                .map(com.noteweave.team.kb.model.KnowledgeBase::getId)
                .toList();
    }

    private ChatMessage persistMessage(Long sessionId, ChatMessageRole role, String content, String tokenUsageJson, String errorCode) {
        return transactionTemplate.execute(status -> {
            ChatMessage message = createMessage(sessionId, role, content, tokenUsageJson, errorCode);
            return chatMessageRepository.save(message);
        });
    }

    private ChatMessage createMessage(Long sessionId, ChatMessageRole role, String content, String tokenUsageJson, String errorCode) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_EMPTY);
        }
        int nextSeq = chatMessageRepository.findTopBySessionIdOrderByMessageSeqDesc(sessionId)
                .map(existing -> existing.getMessageSeq() + 1)
                .orElse(1);
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setMessageSeq(nextSeq);
        message.setRole(role);
        message.setContent(content);
        message.setMessageType(ChatMessageType.TEXT);
        message.setStatus(ChatMessageStatus.COMPLETED);
        message.setRequestId(RequestIdHolder.get());
        message.setTokenUsageJson(tokenUsageJson);
        message.setErrorCode(errorCode);
        return message;
    }

    private List<ChatMessage> recentMessages(Long sessionId) {
        List<ChatMessage> all = chatMessageRepository.findBySessionIdOrderByMessageSeqAsc(sessionId);
        return all.size() <= 4 ? all : all.subList(Math.max(0, all.size() - 4), all.size());
    }

    private Long persistRetrievalTrace(
            Long userId,
            ChatSession session,
            Long messageId,
            String queryText,
            long latencyMs,
            HybridRetriever.HybridRetrievalResult retrieval,
            List<EvidenceItem> evidenceItems
    ) {
        Long traceId = retrievalTraceService.createTrace(RetrievalTraceCreateRequest.builder()
                .userId(userId)
                .spaceId(session.getSpaceId())
                .sessionId(session.getId())
                .messageId(messageId)
                .scene("TEAM_RAG_CHAT")
                .queryText(queryText)
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
        return traceId;
    }

    private com.noteweave.team.rag.retriever.RetrievedChunk toRetrievedChunk(RetrievalHit hit) {
        Integer indexVersion = hit.metadata() == null ? null : (Integer) hit.metadata().get("indexVersion");
        String sourceVersion = resolveSourceVersion(hit.metadata(), indexVersion);
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
                sourceVersion
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

    private String resolveSourceVersion(Map<String, Object> metadata, Integer indexVersion) {
        if (metadata != null && metadata.containsKey("publishedVersionId")) {
            Long publishedVersionId = longValue(metadata.get("publishedVersionId"));
            return publishedVersionId == null ? "unknown" : String.valueOf(publishedVersionId);
        }
        return indexVersion == null ? "unknown" : String.valueOf(indexVersion);
    }

    private AssistantOutcome persistAssistantOutcome(
            ChatSession session,
            ChatMessage userMessage,
            Long retrievalTraceId,
            String answer,
            String tokenUsageJson,
            List<EvidenceItem> evidenceItems,
            Long artifactId,
            Long artifactSpaceId,
            Long taskId,
            String toolName
    ) {
        return transactionTemplate.execute(status -> {
            ChatMessage assistantMessage = chatMessageRepository.save(
                    createMessage(session.getId(), ChatMessageRole.ASSISTANT, answer, tokenUsageJson, null, artifactId)
            );
            List<CitationResponse> citations = evidenceItems.isEmpty()
                    ? List.of()
                    : citationService.saveForAssistantMessage(assistantMessage.getId(), session.getSpaceId(), evidenceItems, retrievalTraceId);
            TeamAskResponse response = TeamAskResponse.builder()
                    .userMessageId(userMessage.getId())
                    .assistantMessageId(assistantMessage.getId())
                    .answer(answer)
                    .artifactId(artifactId)
                    .artifactSpaceId(artifactSpaceId)
                    .taskId(taskId)
                    .toolName(toolName)
                    .citations(citations)
                    .build();
            return new AssistantOutcome(response, assistantMessage);
        });
    }

    private ChatMessage createMessage(Long sessionId, ChatMessageRole role, String content, String tokenUsageJson, String errorCode, Long artifactId) {
        ChatMessage message = createMessage(sessionId, role, content, tokenUsageJson, errorCode);
        message.setArtifactId(artifactId);
        return message;
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
                            .metadataJson(writeJson(hit.metadata()))
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize chat metadata", ex);
        }
    }

    private record AssistantOutcome(TeamAskResponse response, ChatMessage assistantMessage) {
    }
}
