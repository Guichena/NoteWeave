package com.noteweave.memory.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatMessageRole;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.model.ChatSessionKind;
import com.noteweave.citation.dto.CitationResponse;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.claim.service.ClaimWritebackService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemoryWritebackServiceTest {

    @Mock
    private MemoryWritebackStrategy memoryWritebackStrategy;

    @Mock
    private SessionSummaryService sessionSummaryService;

    @Mock
    private MemoryItemService memoryItemService;

    @Mock
    private SpaceMemoryService spaceMemoryService;

    @Mock
    private UserMemoryService userMemoryService;

    @Mock
    private ClaimWritebackService claimWritebackService;

    private MemoryWritebackService service;

    @BeforeEach
    void setUp() {
        service = new MemoryWritebackService(
                memoryWritebackStrategy,
                sessionSummaryService,
                memoryItemService,
                spaceMemoryService,
                userMemoryService,
                claimWritebackService
        );
    }

    @Test
    void shouldContinueSessionSummaryWritebackWhenClaimWritebackFails() {
        ChatSession session = session();
        ChatMessage userMessage = message(101L, ChatMessageRole.USER, "Please compare claim continuity across questions.");
        ChatMessage assistantMessage = message(102L, ChatMessageRole.ASSISTANT, "No relevant information was found.");
        MemoryWriteDecision decision = MemoryWriteDecision.builder()
                .writeSessionSummary(true)
                .writeSpaceMemory(false)
                .writeUserMemory(false)
                .topic("claim continuity")
                .spaceSummary("The user asked about claim continuity across questions.")
                .importanceScore(BigDecimal.valueOf(0.7d))
                .confidenceScore(BigDecimal.valueOf(0.72d))
                .reason("meaningful round")
                .build();

        when(userMemoryService.isWriteEnabled(session.getUserId())).thenReturn(true);
        when(memoryWritebackStrategy.decide(session, userMessage, assistantMessage)).thenReturn(decision);
        doThrow(new BusinessException(ErrorCode.LLM_JSON_PARSE_FAILED, "bad claim json"))
                .when(claimWritebackService)
                .writeAfterRound(session, userMessage, assistantMessage, List.of());

        service.writeAfterRound(session, userMessage, assistantMessage, List.of());

        verify(claimWritebackService).writeAfterRound(session, userMessage, assistantMessage, List.of());
        verify(sessionSummaryService).createAfterRound(session, userMessage, assistantMessage, List.of(), decision);
    }

    private ChatSession session() {
        ChatSession session = new ChatSession();
        session.setId(11L);
        session.setUserId(7L);
        session.setSpaceId(3L);
        session.setResearchQuestionId(21L);
        session.setSessionKind(ChatSessionKind.FORMAL);
        return session;
    }

    private ChatMessage message(Long id, ChatMessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setSessionId(11L);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
