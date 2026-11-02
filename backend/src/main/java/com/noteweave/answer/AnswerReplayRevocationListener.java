package com.noteweave.answer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AnswerReplayRevocationListener {
    private final SessionEventMux runs;
    private final ConversationEventMux conversations;
    private final ObjectProvider<AnswerRealtimeBridge> bridges;

    public AnswerReplayRevocationListener(SessionEventMux runs, ConversationEventMux conversations,
            ObjectProvider<AnswerRealtimeBridge> bridges) {
        this.runs = runs;
        this.conversations = conversations;
        this.bridges = bridges;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void revoke(AnswerReplayRevoked event) {
        runs.redact(event.runId());
        conversations.redact(event.conversationId(), event.runId());
        AnswerRealtimeBridge bridge = bridges.getIfAvailable();
        if (bridge != null) bridge.redact(event.conversationId(), event.runId());
    }
}
