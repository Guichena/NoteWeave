package com.noteweave.memory.service;

import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.citation.dto.CitationResponse;
import java.util.List;

public record MemoryWriteContext(
        ChatSession session,
        ChatMessage userMessage,
        ChatMessage assistantMessage,
        List<CitationResponse> citations,
        MemoryWriteDecision decision
) {
}
