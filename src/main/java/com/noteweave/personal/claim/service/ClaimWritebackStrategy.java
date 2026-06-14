package com.noteweave.personal.claim.service;

import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.model.ChatSessionKind;
import org.springframework.stereotype.Component;

@Component
public class ClaimWritebackStrategy {

    public boolean shouldWrite(ChatSession session, ChatMessage userMessage, ChatMessage assistantMessage) {
        if (session == null || userMessage == null || assistantMessage == null) {
            return false;
        }
        if (session.getResearchQuestionId() == null) {
            return false;
        }
        if (session.getSessionKind() != ChatSessionKind.FORMAL) {
            return false;
        }
        String userContent = normalize(userMessage.getContent());
        String assistantContent = normalize(assistantMessage.getContent());
        if (userContent.length() < 12 || assistantContent.length() < 24) {
            return false;
        }
        return !assistantContent.equalsIgnoreCase("ok") && !assistantContent.equalsIgnoreCase("thanks");
    }

    private String normalize(String content) {
        return content == null ? "" : content.trim();
    }
}
