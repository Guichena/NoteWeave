package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.noteweave.answer.*;
import com.noteweave.conversation.ConversationTurnModule;
import com.noteweave.memory.MemoryApplicationFeedbackService;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FrozenCitationSseControllerTest {
    @Test
    void completedSseSendsFrozenProtocolBeforeBodyWithoutInventingSequence() throws Exception {
        AnswerRunService runs = mock(AnswerRunService.class);
        ChatService chat = mock(ChatService.class);
        EvidenceReadyEvent protocol = new EvidenceReadyEvent("citation-binding-v1", List.of("passage:1"),
                List.of(new EvidenceReadyEvent.Binding(1, "passage:1", List.of("citation:1"))));
        AnswerRunResponse snapshot = new AnswerRunResponse("run", "ws", "conv", "QA", "COMPLETED",
                "query", "answer", "request", "plan", "model", 100, false, List.of(), 1, "FINAL",
                "正文[1]", null, null, null, "", "", null, "SUFFICIENT", protocol, 7L);
        when(runs.get("ws", "run")).thenReturn(snapshot);
        when(runs.eventsAfter("ws", "run", 0)).thenReturn(List.of());
        when(runs.currentEventSequence("ws", "run")).thenReturn(7L);
        String body = request(chat, runs, 0);
        assertThat(body).contains("event:run.snapshot", "\"citation_protocol\"", "\"protocol_state\":\"BINDING_V1\"",
                "\"event_sequence\":7", "event:answer.snapshot", "正文[1]");
        assertThat(body.indexOf("event:run.snapshot")).isLessThan(body.indexOf("event:answer.snapshot"));
        assertThat(body).doesNotContain("id:8");
    }

    @Test
    void reconnectSseKeepsOriginalCursorAndDeliversProtocolBeforeFollowingDelta() throws Exception {
        AnswerRunService runs = mock(AnswerRunService.class);
        ChatService chat = mock(ChatService.class);
        EvidenceReadyEvent protocol = new EvidenceReadyEvent("citation-binding-v1", List.of("passage:1"),
                List.of(new EvidenceReadyEvent.Binding(1, "passage:1", List.of("citation:1"))));
        AnswerRunResponse snapshot = new AnswerRunResponse("run", "ws", "conv", "QA", "GENERATING",
                "query", "answer", "request", "plan", "model", 100, false, List.of(), 1, "STREAMING",
                "仅是草稿", null, null, null, "", "", null, "SUFFICIENT", protocol, 7L);
        when(runs.get("ws", "run")).thenReturn(snapshot);
        when(runs.eventsAfter("ws", "run", 3)).thenReturn(List.of());
        when(runs.hasActiveStream("ws", "run")).thenReturn(true);
        doAnswer(call -> {
            Consumer<AnswerLiveEvent> sink = call.getArgument(3);
            sink.accept(new AnswerLiveEvent(4L, "answer.delta", "实时正文[1]", Instant.now()));
            return null;
        }).when(chat).followAuthorizedRun(eq("ws"), eq("run"), eq(3L), any());
        String body = request(chat, runs, 3);
        assertThat(body.indexOf("event:run.snapshot")).isLessThan(body.indexOf("event:answer.delta"));
        assertThat(body).contains("id:4", "实时正文[1]");
        verify(chat).followAuthorizedRun(eq("ws"), eq("run"), eq(3L), any());
    }

    private String request(ChatService chat, AnswerRunService runs, long cursor) throws Exception {
        ChatController controller = new ChatController(chat, mock(ConversationTurnModule.class),
                mock(NoteAnswerSourceService.class), Runnable::run, Runnable::run, runs,
                mock(AnswerCancellationRegistry.class), mock(MemoryApplicationFeedbackService.class));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        MvcResult pending = mvc.perform(get("/api/v2/workspaces/ws/answer-runs/run/events")
                .param("after", Long.toString(cursor))).andReturn();
        return mvc.perform(asyncDispatch(pending)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
