package com.noteweave.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.artifact.model.Artifact;
import com.noteweave.artifact.repository.ArtifactRepository;
import com.noteweave.chat.dto.ChatMessageResponse;
import com.noteweave.chat.dto.ChatSessionResponse;
import com.noteweave.chat.dto.CreateChatSessionRequest;
import com.noteweave.chat.model.ChatRuntimeStatus;
import com.noteweave.chat.model.ChatDraftStatus;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.model.ChatSessionKind;
import com.noteweave.chat.model.ChatSessionScope;
import com.noteweave.chat.model.ChatSessionStatus;
import com.noteweave.chat.model.ChatSessionType;
import com.noteweave.chat.repository.ChatMessageRepository;
import com.noteweave.chat.repository.ChatSessionRepository;
import com.noteweave.chat.repository.ChatSessionScopeRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.personal.question.model.ResearchQuestion;
import com.noteweave.personal.question.service.ResearchQuestionService;
import com.noteweave.team.kb.model.KnowledgeBaseStatus;
import com.noteweave.team.kb.repository.KnowledgeBaseRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatSessionScopeRepository chatSessionScopeRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ResourceAccessService resourceAccessService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ArtifactRepository artifactRepository;
    private final ResearchQuestionService researchQuestionService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ChatSessionResponse createTeamSession(Long userId, CreateChatSessionRequest request) {
        resourceAccessService.requireViewSpace(userId, request.getSpaceId());
        if (request.getSessionType() != ChatSessionType.TEAM_CHAT) {
            throw new BusinessException(ErrorCode.CHAT_SESSION_TYPE_UNSUPPORTED);
        }
        if (request.getScopeType() == com.noteweave.chat.model.ChatScopeType.KNOWLEDGE_BASE) {
            for (Long scopeId : request.getScopeIds()) {
                var kb = knowledgeBaseRepository.findByIdAndStatus(scopeId, KnowledgeBaseStatus.ACTIVE)
                        .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
                if (!kb.getSpaceId().equals(request.getSpaceId())) {
                    throw new BusinessException(ErrorCode.SPACE_ACCESS_DENIED, "knowledge base is outside current space");
                }
            }
        }
        Long boundProjectId = resolveBoundProject(userId, request);
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setSpaceId(request.getSpaceId());
        session.setSessionType(ChatSessionType.TEAM_CHAT);
        session.setSessionKind(request.getSessionKind());
        session.setTitle(request.getTitle().trim());
        session.setScopeType(request.getScopeType());
        session.setResearchProjectId(boundProjectId);
        session.setResearchQuestionId(request.getResearchQuestionId());
        session.setScopeIdsSnapshotJson(writeJson(request.getScopeIds()));
        session.setStatus(ChatSessionStatus.ACTIVE);
        session.setRuntimeStatus(ChatRuntimeStatus.IDLE);
        session.setDraftStatus(request.getSessionKind() == ChatSessionKind.DRAFT ? ChatDraftStatus.DRAFT_ACTIVE : null);
        session.setLastActiveAt(LocalDateTime.now());
        session = chatSessionRepository.save(session);

        for (Long scopeId : request.getScopeIds()) {
            ChatSessionScope scope = new ChatSessionScope();
            scope.setSessionId(session.getId());
            scope.setScopeType(request.getScopeType().name());
            scope.setScopeId(scopeId);
            chatSessionScopeRepository.save(scope);
        }
        return toResponse(session, request.getScopeIds());
    }

    @Transactional(readOnly = true)
    public List<ChatSessionResponse> listBySpace(Long userId, Long spaceId) {
        resourceAccessService.requireViewSpace(userId, spaceId);
        return chatSessionRepository.findBySpaceIdAndStatusOrderByUpdatedAtDesc(spaceId, ChatSessionStatus.ACTIVE).stream()
                .map(session -> toResponse(session, listScopeIds(session.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatSessionResponse getSession(Long userId, Long sessionId) {
        ChatSession session = getRequiredActiveSession(sessionId);
        resourceAccessService.requireViewSpace(userId, session.getSpaceId());
        return toResponse(session, listScopeIds(session.getId()));
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> listMessages(Long userId, Long sessionId) {
        ChatSession session = getRequiredActiveSession(sessionId);
        resourceAccessService.requireViewSpace(userId, session.getSpaceId());
        List<ChatMessage> messages = chatMessageRepository.findBySessionIdOrderByMessageSeqAsc(sessionId);
        Map<Long, Long> artifactSpaceIds = loadArtifactSpaceIds(messages);
        return messages.stream()
                .map(message -> ChatMessageResponse.builder()
                        .id(message.getId())
                        .sessionId(message.getSessionId())
                        .messageSeq(message.getMessageSeq())
                        .role(message.getRole())
                        .content(message.getContent())
                        .messageType(message.getMessageType())
                        .status(message.getStatus())
                        .artifactId(message.getArtifactId())
                        .artifactSpaceId(resolveArtifactSpaceId(message, artifactSpaceIds))
                        .requestId(message.getRequestId())
                        .errorCode(message.getErrorCode())
                        .createdAt(message.getCreatedAt())
                        .updatedAt(message.getUpdatedAt())
                        .build())
                .toList();
    }

    private Long resolveArtifactSpaceId(ChatMessage message, Map<Long, Long> artifactSpaceIds) {
        return message.getArtifactId() == null ? null : artifactSpaceIds.get(message.getArtifactId());
    }

    private Map<Long, Long> loadArtifactSpaceIds(List<ChatMessage> messages) {
        Set<Long> artifactIds = messages.stream()
                .map(ChatMessage::getArtifactId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (artifactIds.isEmpty()) {
            return Map.of();
        }
        return artifactRepository.findAllById(artifactIds).stream()
                .collect(Collectors.toMap(Artifact::getId, Artifact::getSpaceId, (left, right) -> left));
    }

    @Transactional(readOnly = true)
    public ChatMessage getRequiredMessage(Long userId, Long messageId) {
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        ChatSession session = getRequiredActiveSession(message.getSessionId());
        resourceAccessService.requireViewSpace(userId, session.getSpaceId());
        return message;
    }

    @Transactional(readOnly = true)
    public ChatSession getSessionByMessageId(Long userId, Long messageId) {
        ChatMessage message = getRequiredMessage(userId, messageId);
        return getRequiredActiveSession(message.getSessionId());
    }

    @Transactional(readOnly = true)
    public ChatSession getRequiredActiveSession(Long sessionId) {
        return chatSessionRepository.findByIdAndStatus(sessionId, ChatSessionStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_SESSION_NOT_FOUND));
    }

    @Transactional
    public ChatSessionResponse convertDraftToFormal(Long userId, Long sessionId) {
        ChatSession session = chatSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        resourceAccessService.requireViewSpace(userId, session.getSpaceId());
        if (session.getSessionKind() != ChatSessionKind.DRAFT || session.getDraftStatus() != ChatDraftStatus.DRAFT_ACTIVE) {
            throw new BusinessException(ErrorCode.CHAT_DRAFT_INVALID_STATE);
        }
        session.setSessionKind(ChatSessionKind.FORMAL);
        session.setDraftStatus(ChatDraftStatus.CONVERTED);
        session.setRuntimeStatus(ChatRuntimeStatus.IDLE);
        session.setLastActiveAt(java.time.LocalDateTime.now());
        return toResponse(chatSessionRepository.save(session), listScopeIds(session.getId()));
    }

    @Transactional
    public ChatSessionResponse discardDraft(Long userId, Long sessionId) {
        ChatSession session = chatSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        resourceAccessService.requireViewSpace(userId, session.getSpaceId());
        if (session.getSessionKind() != ChatSessionKind.DRAFT || session.getDraftStatus() != ChatDraftStatus.DRAFT_ACTIVE) {
            throw new BusinessException(ErrorCode.CHAT_DRAFT_INVALID_STATE);
        }
        session.setDraftStatus(ChatDraftStatus.DISCARDED);
        session.setRuntimeStatus(ChatRuntimeStatus.IDLE);
        session.setLastActiveAt(java.time.LocalDateTime.now());
        return toResponse(chatSessionRepository.save(session), listScopeIds(session.getId()));
    }

    @Transactional(readOnly = true)
    public List<Long> listScopeIds(Long sessionId) {
        return chatSessionScopeRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(ChatSessionScope::getScopeId)
                .toList();
    }

    /**
     * Validate and resolve the research-question / research-project binding for a new session.
     * Both are optional; when a question is supplied we trust its owning project so the
     * session is always anchored to a consistent (project, question) pair.
     */
    private Long resolveBoundProject(Long userId, CreateChatSessionRequest request) {
        if (request.getResearchQuestionId() != null) {
            ResearchQuestion question = researchQuestionService.getRequiredQuestion(userId, request.getResearchQuestionId());
            if (request.getResearchProjectId() != null
                    && !request.getResearchProjectId().equals(question.getResearchProjectId())) {
                throw new BusinessException(ErrorCode.RESEARCH_QUESTION_ACCESS_DENIED,
                        "research question does not belong to the supplied research project");
            }
            return question.getResearchProjectId();
        }
        return request.getResearchProjectId();
    }

    private ChatSessionResponse toResponse(ChatSession session, List<Long> scopeIds) {
        return ChatSessionResponse.builder()
                .id(session.getId())
                .userId(session.getUserId())
                .spaceId(session.getSpaceId())
                .sessionType(session.getSessionType())
                .sessionKind(session.getSessionKind())
                .scopeType(session.getScopeType())
                .scopeIds(scopeIds)
                .researchProjectId(session.getResearchProjectId())
                .researchQuestionId(session.getResearchQuestionId())
                .title(session.getTitle())
                .summary(session.getSummary())
                .status(session.getStatus())
                .runtimeStatus(session.getRuntimeStatus().name())
                .draftStatus(session.getDraftStatus())
                .lastActiveAt(session.getLastActiveAt())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize session scope", ex);
        }
    }
}
