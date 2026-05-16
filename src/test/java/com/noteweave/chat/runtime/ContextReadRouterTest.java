package com.noteweave.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.chat.model.ChatSessionKind;
import com.noteweave.chat.model.ChatSessionType;
import com.noteweave.chat.runtime.service.ContextReadPlan;
import com.noteweave.chat.runtime.service.ContextReadRouter;
import org.junit.jupiter.api.Test;

class ContextReadRouterTest {

    private final ContextReadRouter contextReadRouter = new ContextReadRouter();

    @Test
    void shouldKeepDraftSessionsOutOfLongTermMemory() {
        ContextReadPlan plan = contextReadRouter.resolve(ChatSessionKind.DRAFT, ChatSessionType.TEAM_CHAT);

        assertThat(plan.readRecentHistory()).isTrue();
        assertThat(plan.readRetrievalEvidence()).isTrue();
        assertThat(plan.readLongTermMemory()).isFalse();
    }
}
