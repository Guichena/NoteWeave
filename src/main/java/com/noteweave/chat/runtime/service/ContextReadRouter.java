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
                    .readRetrievalEvidence(true)
                    .readLongTermMemory(false)
                    .build();
        }
        return ContextReadPlan.builder()
                .readRecentHistory(true)
                .readRetrievalEvidence(sessionType == ChatSessionType.TEAM_CHAT)
                .readLongTermMemory(false)
                .build();
    }
}
