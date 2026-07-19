package com.noteweave.chat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.answer.AnswerCancellationRegistry;
import com.noteweave.answer.AnswerRunRef;
import com.noteweave.answer.AnswerRunService;
import com.noteweave.conversation.ConversationTurnModule;
import com.noteweave.conversation.SubmitTurnCommand;
import com.noteweave.conversation.TurnReceipt;
import java.util.List;
import java.util.concurrent.Executor;
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
}
