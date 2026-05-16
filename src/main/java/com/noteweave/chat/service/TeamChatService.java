package com.noteweave.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.dto.TeamAskRequest;
import com.noteweave.chat.dto.TeamAskResponse;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatMessageRole;
import com.noteweave.chat.model.ChatMessageStatus;
import com.noteweave.chat.model.ChatMessageType;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.model.ChatSessionKind;
import com.noteweave.chat.model.RetrievalTrace;
import com.noteweave.chat.repository.ChatMessageRepository;
import com.noteweave.chat.repository.RetrievalTraceRepository;
import com.noteweave.citation.dto.CitationResponse;
import com.noteweave.citation.service.CitationService;
import com.noteweave.common.api.RequestIdHolder;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.llm.dto.LlmMessage;
import com.noteweave.llm.dto.LlmOptions;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.model.LlmCallLog;
import com.noteweave.llm.repository.LlmCallLogRepository;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
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
    private final Bm25Retriever bm25Retriever;
    private final EvidencePostProcessor evidencePostProcessor;
    private final TeamRagPromptBuilder teamRagPromptBuilder;
    private final LlmClient llmClient;
    private final CitationService citationService;
    private final RetrievalTraceRepository retrievalTraceRepository;
    private final LlmCallLogRepository llmCallLogRepository;
    private final RagProperties ragProperties;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

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

        Instant retrievalStart = Instant.now();
        List<EvidenceItem> evidenceItems = evidencePostProcessor.process(
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
        long retrievalLatency = Math.max(1L, Duration.between(retrievalStart, Instant.now()).toMillis());
        persistRetrievalTrace(userId, session, userMessage.getId(), question, retrievalLatency, evidenceItems.size());

        if (evidenceItems.isEmpty()) {
            return persistAssistantOutcome(
                    userId,
                    session,
                    userMessage.getId(),
                    ragProperties.prompt().noResultText() + "。当前资料不足，无法给出可靠引用。",
                    writeJson(Map.of("retrievalEmpty", true)),
                    List.of(),
                    new LlmLogPayload("none", "no-llm", question, 0, 0, 1L, true, null)
            );
        }

        PromptMessages prompt = teamRagPromptBuilder.build(question, evidenceItems, recentMessages(sessionId));
        String promptForHash = prompt.messages().size() > 1 ? prompt.messages().get(1).content() : question;
        Instant llmStart = Instant.now();
        try {
            LlmResponse llmResponse = llmClient.chat(
                    prompt.messages().stream().map(message -> new LlmMessage(message.role(), message.content())).toList(),
                    LlmOptions.builder()
                            .temperature(0.3d)
                            .maxTokens(2000)
                            .build()
            );
            long llmLatency = Math.max(1L, Duration.between(llmStart, Instant.now()).toMillis());
            return persistAssistantOutcome(
                    userId,
                    session,
                    userMessage.getId(),
                    llmResponse.content(),
                    writeJson(Map.of(
                            "provider", llmResponse.provider(),
                            "model", llmResponse.model(),
                            "inputTokens", llmResponse.inputTokens(),
                            "outputTokens", llmResponse.outputTokens()
                    )),
                    evidenceItems,
                    new LlmLogPayload(
                            llmResponse.provider(),
                            llmResponse.model(),
                            promptForHash,
                            llmResponse.inputTokens(),
                            llmResponse.outputTokens(),
                            llmLatency,
                            true,
                            null
                    )
            );
        } catch (BusinessException ex) {
            long llmLatency = Math.max(1L, Duration.between(llmStart, Instant.now()).toMillis());
            persistLlmCallLog(
                    userId,
                    session,
                    null,
                    new LlmLogPayload(
                            "unknown",
                            "unknown",
                            promptForHash,
                            0,
                            0,
                            llmLatency,
                            false,
                            ex.getErrorCode().name()
                    )
            );
            throw ex;
        }
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

    private void persistRetrievalTrace(Long userId, ChatSession session, Long messageId, String queryText, long latencyMs, int count) {
        transactionTemplate.executeWithoutResult(status -> {
            RetrievalTrace trace = new RetrievalTrace();
            trace.setUserId(userId);
            trace.setSpaceId(session.getSpaceId());
            trace.setSessionId(session.getId());
            trace.setMessageId(messageId);
            trace.setQueryText(queryText);
            trace.setTopK(ragProperties.retrieval().topK());
            trace.setLatencyMs(latencyMs);
            trace.setRetrievedChunkCount(count);
            retrievalTraceRepository.save(trace);
        });
    }

    private TeamAskResponse persistAssistantOutcome(
            Long userId,
            ChatSession session,
            Long userMessageId,
            String answer,
            String tokenUsageJson,
            List<EvidenceItem> evidenceItems,
            LlmLogPayload llmLogPayload
    ) {
        return transactionTemplate.execute(status -> {
            ChatMessage assistantMessage = chatMessageRepository.save(
                    createMessage(session.getId(), ChatMessageRole.ASSISTANT, answer, tokenUsageJson, null)
            );
            List<CitationResponse> citations = evidenceItems.isEmpty()
                    ? List.of()
                    : citationService.saveForAssistantMessage(assistantMessage.getId(), session.getSpaceId(), evidenceItems);
            saveLlmCallLog(userId, session, assistantMessage.getId(), llmLogPayload);
            return TeamAskResponse.builder()
                    .userMessageId(userMessageId)
                    .assistantMessageId(assistantMessage.getId())
                    .answer(answer)
                    .citations(citations)
                    .build();
        });
    }

    private void persistLlmCallLog(Long userId, ChatSession session, Long messageId, LlmLogPayload payload) {
        transactionTemplate.executeWithoutResult(status -> saveLlmCallLog(userId, session, messageId, payload));
    }

    private void saveLlmCallLog(Long userId, ChatSession session, Long messageId, LlmLogPayload payload) {
        LlmCallLog log = new LlmCallLog();
        log.setUserId(userId);
        log.setSpaceId(session.getSpaceId());
        log.setSessionId(session.getId());
        log.setMessageId(messageId);
        log.setProvider(payload.provider());
        log.setModel(payload.model());
        log.setPromptHash(sha256(payload.prompt()));
        log.setInputTokens(payload.inputTokens());
        log.setOutputTokens(payload.outputTokens());
        log.setLatencyMs(payload.latencyMs());
        log.setSuccess(payload.success());
        log.setErrorCode(payload.errorCode());
        llmCallLogRepository.save(log);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize chat metadata", ex);
        }
    }

    private String sha256(String prompt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((prompt == null ? "" : prompt).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash prompt", ex);
        }
    }

    private record LlmLogPayload(
            String provider,
            String model,
            String prompt,
            int inputTokens,
            int outputTokens,
            long latencyMs,
            boolean success,
            String errorCode
    ) {
    }
}
