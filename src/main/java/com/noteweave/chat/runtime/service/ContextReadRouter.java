package com.noteweave.chat.runtime.service;

import com.noteweave.chat.model.ChatSessionKind;
import com.noteweave.chat.model.ChatSessionType;
import org.springframework.stereotype.Component;

@Component
public class ContextReadRouter {

    public ContextReadPlan resolve(ChatSessionKind sessionKind, ChatSessionType sessionType) {
        if (sessionKind == ChatSessionKind.DRAFT) {
            return ContextReadPlan.builder()
                    .readRecentHistory(true)
                    .readSessionSummary(false)
                    .readSpaceMemory(false)
                    .readUserMemory(false)
                    .readRetrievalEvidence(true)
                    .readLongTermMemory(false)
                    .build();
        }
        return ContextReadPlan.builder()
                .readRecentHistory(true)
                .readSessionSummary(true)
                .readSpaceMemory(true)
                .readUserMemory(true)
                .readRetrievalEvidence(sessionType == ChatSessionType.TEAM_CHAT
                        || sessionType == ChatSessionType.PERSONAL_RESEARCH_CHAT)
                .readLongTermMemory(true)
                .build();
    }
}
