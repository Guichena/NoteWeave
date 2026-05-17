package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatMessageRole;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.model.ChatSessionKind;
import com.noteweave.chat.model.ChatSessionType;
import com.noteweave.memory.service.MemoryWriteDecision;
import com.noteweave.memory.service.MemoryWritebackStrategy;
import org.junit.jupiter.api.Test;

class MemoryWritebackStrategyTest {

    private final MemoryWritebackStrategy strategy = new MemoryWritebackStrategy();

    @Test
    void shouldWriteStablePreferenceFromFormalSession() {
        ChatSession session = new ChatSession();
        session.setSessionKind(ChatSessionKind.FORMAL);
        session.setSessionType(ChatSessionType.TEAM_CHAT);

        ChatMessage userMessage = new ChatMessage();
        userMessage.setRole(ChatMessageRole.USER);
        userMessage.setContent("Please remember that I prefer concise bullet answers for this workspace.");

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setRole(ChatMessageRole.ASSISTANT);
        assistantMessage.setContent("I will keep answers concise and structured.");

        MemoryWriteDecision decision = strategy.decide(session, userMessage, assistantMessage);

        assertThat(decision.writeSessionSummary()).isTrue();
        assertThat(decision.writeSpaceMemory()).isTrue();
        assertThat(decision.writeUserMemory()).isTrue();
        assertThat(decision.confidenceScore()).isGreaterThanOrEqualTo(java.math.BigDecimal.valueOf(0.8d));
    }

    @Test
    void shouldSkipDraftAndSensitiveInputs() {
        ChatSession session = new ChatSession();
        session.setSessionKind(ChatSessionKind.DRAFT);
        session.setSessionType(ChatSessionType.TEAM_CHAT);

        ChatMessage userMessage = new ChatMessage();
        userMessage.setRole(ChatMessageRole.USER);
        userMessage.setContent("My password is Password123 and my token is secret-token.");

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setRole(ChatMessageRole.ASSISTANT);
        assistantMessage.setContent("I will not store sensitive details.");

        MemoryWriteDecision decision = strategy.decide(session, userMessage, assistantMessage);

        assertThat(decision.writeSessionSummary()).isFalse();
        assertThat(decision.writeSpaceMemory()).isFalse();
        assertThat(decision.writeUserMemory()).isFalse();
        assertThat(decision.reason()).isNotBlank();
    }
}
