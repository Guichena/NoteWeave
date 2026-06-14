package com.noteweave.memory.service;

import com.noteweave.chat.model.ChatSession;
import com.noteweave.citation.dto.CitationResponse;
import com.noteweave.personal.claim.service.ClaimWritebackService;
import com.noteweave.memory.model.MemoryType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryWritebackService {

    private final MemoryWritebackStrategy memoryWritebackStrategy;
    private final SessionSummaryService sessionSummaryService;
    private final MemoryItemService memoryItemService;
    private final SpaceMemoryService spaceMemoryService;
    private final UserMemoryService userMemoryService;
    private final ClaimWritebackService claimWritebackService;

    @Transactional
    public void writeAfterRound(
            ChatSession session,
            com.noteweave.chat.model.ChatMessage userMessage,
            com.noteweave.chat.model.ChatMessage assistantMessage,
            List<CitationResponse> citations
    ) {
        if (session == null || userMessage == null || assistantMessage == null) {
            return;
        }
        if (!userMemoryService.isWriteEnabled(session.getUserId())) {
            return;
        }
        MemoryWriteDecision decision = memoryWritebackStrategy.decide(session, userMessage, assistantMessage);
        try {
            claimWritebackService.writeAfterRound(session, userMessage, assistantMessage, citations);
        } catch (RuntimeException ex) {
            log.warn("Skipping claim writeback after round for session {} question {}",
                    session.getId(), session.getResearchQuestionId(), ex);
        }
        if (!decision.writeSessionSummary() && !decision.writeSpaceMemory() && !decision.writeUserMemory()) {
            return;
        }
        MemoryWriteContext context = new MemoryWriteContext(session, userMessage, assistantMessage, citations, decision);
        if (decision.writeSessionSummary()) {
            sessionSummaryService.createAfterRound(session, userMessage, assistantMessage, citations, decision);
            session.setSummary(context.decision().spaceSummary());
        }
        if (decision.writeSpaceMemory()) {
            memoryItemService.upsert(
                    session.getUserId(),
                    session.getSpaceId(),
                    MemoryType.SPACE_CONTEXT,
                    decision.topic(),
                    decision.spaceSummary(),
                    "CHAT_SESSION",
                    session.getId(),
                    decision.importanceScore(),
                    decision.confidenceScore(),
                    false,
                    LocalDateTime.now().plusDays(14)
            );
            spaceMemoryService.refreshFromItems(session.getUserId(), session.getSpaceId(), memoryItemService.listContextEligible(session.getUserId(), session.getSpaceId()));
        }
        if (decision.writeUserMemory() && decision.userPreferenceSummary() != null) {
            memoryItemService.upsert(
                    session.getUserId(),
                    null,
                    MemoryType.USER_PREFERENCE,
                    decision.topic(),
                    decision.userPreferenceSummary(),
                    "CHAT_SESSION",
                    session.getId(),
                    decision.importanceScore(),
                    decision.confidenceScore(),
                    false,
                    null
            );
            userMemoryService.refreshFromItems(session.getUserId(), memoryItemService.listContextEligible(session.getUserId(), null));
        }
    }
}
