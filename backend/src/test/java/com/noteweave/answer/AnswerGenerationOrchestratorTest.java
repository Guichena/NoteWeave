package com.noteweave.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

class AnswerGenerationOrchestratorTest {

    @Test
    void legacyGenerationMustNotCompleteWhenProviderReturnsBlankContent() {
        AnswerRunService answerRunService = mock(AnswerRunService.class);
        AnswerGenerationGateway gateway = mock(AnswerGenerationGateway.class);
        when(answerRunService.requireByAssistantRequest("request-1")).thenReturn(null);
        when(gateway.load("request-1"))
                .thenReturn(new AnswerGenerationMaterial("message-1", "workspace-1", "draft", List.of(), 100));
        when(gateway.prepareDraft("draft")).thenReturn("prepared");
        when(gateway.generate(anyString(), anyInt(), any())).thenReturn("  ");

        AnswerGenerationOrchestrator orchestrator = orchestrator(answerRunService, gateway);
        List<AnswerDeliveryEvent> events = new ArrayList<>();

        orchestrator.stream("request-1", events::add);

        assertThat(events).extracting(AnswerDeliveryEvent::eventType).containsExactly("answer.failed");
        assertThat(events.get(0).data()).isEqualTo("ANSWER_LLM_EMPTY_RESPONSE");
        verify(gateway, never()).persistContent(anyString(), anyString(), anyString());
    }

    @Test
    void completedReplayMustNotEmitSuccessForBlankHistoricalContent() {
        AnswerRunService answerRunService = mock(AnswerRunService.class);
        AnswerGenerationGateway gateway = mock(AnswerGenerationGateway.class);
        when(answerRunService.requireByAssistantRequest("request-2"))
                .thenReturn(new AnswerRunRef("run-2", "workspace-1", "conversation-1", "message-2", "request-2", "COMPLETED"));
        when(gateway.load("request-2"))
                .thenReturn(new AnswerGenerationMaterial("message-2", "workspace-1", "", List.of(), 100));

        AnswerGenerationOrchestrator orchestrator = orchestrator(answerRunService, gateway);
        List<AnswerDeliveryEvent> events = new ArrayList<>();

        orchestrator.stream("request-2", events::add);

        assertThat(events).extracting(AnswerDeliveryEvent::eventType).containsExactly("answer.failed");
        assertThat(events.get(0).data()).isEqualTo("ANSWER_LLM_EMPTY_RESPONSE");
        verify(gateway, never()).replayChunks(any());
    }

    private AnswerGenerationOrchestrator orchestrator(
            AnswerRunService answerRunService,
            AnswerGenerationGateway gateway
    ) {
        return new AnswerGenerationOrchestrator(
                answerRunService,
                mock(AnswerCancellationRegistry.class),
                mock(SessionEventMux.class),
                mock(ConversationEventMux.class),
                gateway,
                mock(ScheduledExecutorService.class),
                mock(ScheduledExecutorService.class));
    }
}
