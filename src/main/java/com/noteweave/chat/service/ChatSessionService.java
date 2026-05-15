package com.noteweave.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.dto.ChatMessageResponse;
import com.noteweave.chat.dto.ChatSessionResponse;
import com.noteweave.chat.dto.CreateChatSessionRequest;
import com.noteweave.chat.model.ChatRuntimeStatus;
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
import com.noteweave.team.kb.model.KnowledgeBaseStatus;
import com.noteweave.team.kb.repository.KnowledgeBaseRepository;
import java.util.List;
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
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setSpaceId(request.getSpaceId());
        session.setSessionType(ChatSessionType.TEAM_CHAT);
        session.setSessionKind(ChatSessionKind.FORMAL);
        session.setTitle(request.getTitle().trim());
        session.setScopeType(request.getScopeType());
        session.setScopeIdsSnapshotJson(writeJson(request.getScopeIds()));
        session.setStatus(ChatSessionStatus.ACTIVE);
        session.setRuntimeStatus(ChatRuntimeStatus.IDLE);
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
        return chatMessageRepository.findBySessionIdOrderByMessageSeqAsc(sessionId).stream()
                .map(message -> ChatMessageResponse.builder()
                        .id(message.getId())
                        .sessionId(message.getSessionId())
                        .messageSeq(message.getMessageSeq())
                        .role(message.getRole())
                        .content(message.getContent())
                        .messageType(message.getMessageType())
                        .artifactId(message.getArtifactId())
                        .requestId(message.getRequestId())
                        .errorCode(message.getErrorCode())
                        .createdAt(message.getCreatedAt())
                        .updatedAt(message.getUpdatedAt())
                        .build())
                .toList();
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

    @Transactional(readOnly = true)
    public List<Long> listScopeIds(Long sessionId) {
        return chatSessionScopeRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(ChatSessionScope::getScopeId)
                .toList();
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
                .title(session.getTitle())
                .summary(session.getSummary())
                .status(session.getStatus())
                .runtimeStatus(session.getRuntimeStatus().name())
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
