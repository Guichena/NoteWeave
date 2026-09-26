package com.noteweave.conversation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ConversationControllerSseLifecycleTest {
    @Test
    void completionIsIdempotentAfterTimeoutOrDisconnect() {
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean closed = new AtomicBoolean(true);

        ConversationController.completeOnce(emitter, closed);
        ConversationController.completeWithErrorOnce(emitter, closed, new IllegalStateException("late"));

        verify(emitter, never()).complete();
        verify(emitter, never()).completeWithError(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void firstTerminalSignalWins() {
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean closed = new AtomicBoolean();

        ConversationController.completeOnce(emitter, closed);
        ConversationController.completeWithErrorOnce(emitter, closed, new IllegalStateException("late"));

        verify(emitter).complete();
        verify(emitter, never()).completeWithError(org.mockito.ArgumentMatchers.any());
    }
}
