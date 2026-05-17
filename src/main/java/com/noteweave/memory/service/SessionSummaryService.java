package com.noteweave.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.citation.dto.CitationResponse;
import com.noteweave.memory.model.SessionSummary;
import com.noteweave.memory.repository.SessionSummaryRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SessionSummaryService {

    private final SessionSummaryRepository sessionSummaryRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SessionSummary createAfterRound(
            ChatSession session,
            ChatMessage userMessage,
            ChatMessage assistantMessage,
            List<CitationResponse> citations,
            MemoryWriteDecision decision
    ) {
        SessionSummary summary = new SessionSummary();
        summary.setUserId(session.getUserId());
        summary.setSpaceId(session.getSpaceId());
        summary.setSessionId(session.getId());
        summary.setTopic(decision.topic());
        summary.setQueryType(session.getSessionType().name());
        summary.setScopeType(session.getScopeType().name());
        summary.setScopeId(null);
        summary.setSummary(buildSummary(userMessage, assistantMessage));
        summary.setReferenceSourceJson(writeJson(citations == null ? List.of() : citations.stream()
                .map(citation -> java.util.Map.of(
                        "citationId", citation.getId(),
                        "sourceType", citation.getSourceType(),
                        "sourceId", citation.getSourceId()))
                .toList()));
        summary.setImportanceScore(decision.importanceScore());
        summary.setConfidenceScore(decision.confidenceScore());
        summary.setExpiresAt(LocalDateTime.now().plusDays(30));
        return sessionSummaryRepository.save(summary);
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> listBySession(Long userId, Long sessionId) {
        return sessionSummaryRepository.findByUserIdAndSessionIdOrderByCreatedAtDesc(userId, sessionId);
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> retrieveRelevant(Long userId, Long spaceId) {
        return sessionSummaryRepository.findRelevant(userId, spaceId, LocalDateTime.now()).stream()
                .limit(3)
                .toList();
    }

    private String buildSummary(ChatMessage userMessage, ChatMessage assistantMessage) {
        String question = userMessage == null ? "" : safeTrim(userMessage.getContent());
        String answer = assistantMessage == null ? "" : safeTrim(assistantMessage.getContent());
        return "Question: " + question + "\nAnswer: " + answer;
    }

    private String safeTrim(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }
}
