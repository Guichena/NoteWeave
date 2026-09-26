package com.noteweave.chat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.answer.AnswerCancellationRegistry;
import com.noteweave.answer.AnswerRunRef;
import com.noteweave.answer.AnswerRunResponse;
import com.noteweave.answer.AnswerRunService;
import com.noteweave.conversation.ConversationTurnModule;
import com.noteweave.conversation.SubmitTurnCommand;
import com.noteweave.conversation.TurnReceipt;
import java.util.List;
import java.util.concurrent.Executor;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

class ChatControllerDispatchTest {

    @Test
    void sendingMessageShouldStartRunWithoutConversationSubscriber() {
        ChatService chatService = mock(ChatService.class);
        ConversationTurnModule conversationTurnModule = mock(ConversationTurnModule.class);
        AnswerRunService answerRunService = mock(AnswerRunService.class);
        SendMessageRequest request = new SendMessageRequest("question", "QA", "client-1", List.of());
        SubmitTurnCommand command = SubmitTurnCommand.from("conversation-1", request);
        TurnReceipt response = new TurnReceipt(
                "submission-1", "ANSWER", "message-1", "assistant-1", "request-1",
                "/legacy", "run-1", null, "/events", false, List.of(), false);
        when(conversationTurnModule.submitNewTurn(command)).thenReturn(response);
        when(answerRunService.requireByAssistantRequest("request-1")).thenReturn(
                new AnswerRunRef("run-1", "workspace-1", "conversation-1", "assistant-1", "request-1", "GENERATING"));
        Executor direct = Runnable::run;
        ChatController controller = new ChatController(
                chatService,
                conversationTurnModule,
                mock(NoteAnswerSourceService.class),
                direct,
                direct,
                answerRunService,
                mock(AnswerCancellationRegistry.class)
        );

        controller.sendMessage("conversation-1", request);

        verify(chatService).startRun("workspace-1", "run-1");
    }

    @Test
    void answerRunStreamShouldAuthorizeBeforeDispatchingAsyncFollow() {
        ChatService chatService = mock(ChatService.class);
        AnswerRunService answerRunService = mock(AnswerRunService.class);
        AnswerRunResponse snapshot = mock(AnswerRunResponse.class);
        when(snapshot.status()).thenReturn("GENERATING");
        when(answerRunService.eventsAfter("workspace-1", "run-1", 0)).thenReturn(List.of());
        when(answerRunService.get("workspace-1", "run-1")).thenReturn(snapshot);
        when(answerRunService.hasActiveStream("workspace-1", "run-1")).thenReturn(true);
        Executor direct = Runnable::run;
        ChatController controller = new ChatController(
                chatService,
                mock(ConversationTurnModule.class),
                mock(NoteAnswerSourceService.class),
                direct,
                direct,
                answerRunService,
                mock(AnswerCancellationRegistry.class)
        );

        controller.streamAnswerRun(
                "workspace-1", "run-1", 0, null, mock(HttpServletResponse.class));

        verify(chatService).requireRunStreamAccess("workspace-1", "run-1");
        verify(chatService).followAuthorizedRun(
                org.mockito.ArgumentMatchers.eq("workspace-1"),
                org.mockito.ArgumentMatchers.eq("run-1"),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.any());
        verify(chatService, never()).followRun(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
    }
}
