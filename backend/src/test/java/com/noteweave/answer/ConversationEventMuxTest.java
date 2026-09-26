package com.noteweave.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConversationEventMuxTest {

    @Test
    void shouldFailClosedWhenConfiguredConversationBridgeCannotAllocateGlobalCursor() {
        AnswerRealtimeBridge unavailableBridge = new AnswerRealtimeBridge() {
            @Override
            public void publish(String runId, AnswerLiveEvent event) {
            }

            @Override
            public List<AnswerLiveEvent> readAfter(String runId, long after, Duration blockTimeout) {
                return List.of();
            }

            @Override
            public ConversationLiveEvent publishConversation(
                    String conversationId,
                    String runId,
                    AnswerLiveEvent event
            ) {
                throw new AnswerRealtimeBridgeUnavailableException(
                        "redis unavailable", new IllegalStateException("connection refused"));
            }

            @Override
            public List<ConversationLiveEvent> readConversationAfter(
                    String conversationId, long after, Duration blockTimeout
            ) {
                throw new AnswerRealtimeBridgeUnavailableException(
                        "redis unavailable", new IllegalStateException("connection refused"));
            }

            @Override
            public void signalCancellation(String runId) {
            }

            @Override
            public boolean isCancellationRequested(String runId) {
                return false;
            }
        };
        ConversationEventMux mux = new ConversationEventMux(
                unavailableBridge, Runnable::run, Runnable::run,
                new SimpleMeterRegistry(), 64, 16);

        assertThatThrownBy(() -> mux.publish(
                "conversation-1", "run-1", runEvent(1, "answer.delta", "value")))
                .isInstanceOf(AnswerRealtimeBridgeUnavailableException.class)
                .hasMessageContaining("redis unavailable");
    }

    @Test
    void shouldAggregateMultipleRunsUnderOneConversationCursor() throws Exception {
        ConversationEventMux mux = new ConversationEventMux(
                (AnswerRealtimeBridge) null, Runnable::run, Runnable::run,
                new SimpleMeterRegistry(), 64, 16);
        List<ConversationLiveEvent> received = new CopyOnWriteArrayList<>();
        Thread follower = new Thread(() -> mux.follow(
                "conversation-1", 0, received::add, Duration.ofSeconds(2), true));
        follower.start();

        mux.publish("conversation-1", "run-1", runEvent(1, "answer.delta", "one"));
        mux.publish("conversation-1", "run-2", runEvent(7, "answer.delta", "two"));
        mux.publish("conversation-1", "run-2", runEvent(8, "answer.completed", "message-2"));
        follower.join(2000);

        assertThat(follower.isAlive()).isFalse();
        assertThat(received).extracting(ConversationLiveEvent::sequence)
                .containsExactly(1L, 2L, 3L);
        assertThat(received).extracting(ConversationLiveEvent::runId)
                .containsExactly("run-1", "run-2", "run-2");
        assertThat(received).extracting(ConversationLiveEvent::runSequence)
                .containsExactly(1L, 7L, 8L);
    }

    @Test
    void shouldReclaimIdleConversationChannelsAfterRetentionWindow() throws Exception {
        ConversationEventMux mux = new ConversationEventMux(
                null,
                Runnable::run,
                Runnable::run,
                new SimpleMeterRegistry(),
                64,
                16,
                Duration.ofMillis(1),
                100
        );
        ConversationLiveEvent first = mux.publish(
                "conversation-retained", "run-1", runEvent(1, "answer.delta", "old"));
        Thread.sleep(20);

        ConversationLiveEvent next = mux.publish(
                "conversation-retained", "run-2", runEvent(2, "answer.delta", "new"));

        assertThat(first.sequence()).isEqualTo(1L);
        assertThat(next.sequence()).isEqualTo(1L);
    }

    @Test
    void shouldBridgeConversationEventsAcrossInstances() throws Exception {
        SharedBridge bridge = new SharedBridge();
        var bridgeExecutor = Executors.newSingleThreadExecutor();
        try {
            ConversationEventMux owner = new ConversationEventMux(
                    bridge, bridgeExecutor, Runnable::run, new SimpleMeterRegistry(), 64, 16);
            ConversationEventMux peer = new ConversationEventMux(
                    bridge, bridgeExecutor, Runnable::run, new SimpleMeterRegistry(), 64, 16);
            List<ConversationLiveEvent> received = new CopyOnWriteArrayList<>();
            Thread follower = new Thread(() -> peer.follow(
                    "conversation-shared", 0, received::add, Duration.ofSeconds(2), true));
            follower.start();
            Thread.sleep(30);

            owner.publish("conversation-shared", "run-a", runEvent(5, "answer.delta", "remote"));
            owner.publish("conversation-shared", "run-a", runEvent(9, "answer.completed", "done"));
            follower.join(2000);

            assertThat(follower.isAlive()).isFalse();
            assertThat(received).extracting(ConversationLiveEvent::sequence).containsExactly(1L, 2L);
            assertThat(received).extracting(ConversationLiveEvent::runSequence).containsExactly(5L, 9L);
        } finally {
            bridgeExecutor.shutdownNow();
        }
    }

    private AnswerLiveEvent runEvent(long sequence, String type, String data) {
        return new AnswerLiveEvent(sequence, type, data, Instant.now());
    }

    private static final class SharedBridge implements AnswerRealtimeBridge {
        private final Map<String, List<ConversationLiveEvent>> conversationEvents = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

        @Override
        public void publish(String runId, AnswerLiveEvent event) {
        }

        @Override
        public List<AnswerLiveEvent> readAfter(String runId, long after, Duration blockTimeout) {
            return List.of();
        }

        @Override
        public synchronized ConversationLiveEvent publishConversation(
                String conversationId,
                String runId,
                AnswerLiveEvent event
        ) {
            long sequence = sequences.computeIfAbsent(conversationId, ignored -> new AtomicLong())
                    .incrementAndGet();
            ConversationLiveEvent conversationEvent = new ConversationLiveEvent(
                    sequence, runId, event.sequence(), event.eventType(), event.data(), event.occurredAt());
            conversationEvents.computeIfAbsent(conversationId, ignored -> new ArrayList<>())
                    .add(conversationEvent);
            notifyAll();
            return conversationEvent;
        }

        @Override
        public synchronized List<ConversationLiveEvent> readConversationAfter(
                String conversationId,
                long after,
                Duration blockTimeout
        ) {
            List<ConversationLiveEvent> available = after(conversationId, after);
            if (!available.isEmpty()) {
                return available;
            }
            try {
                wait(Math.min(100, Math.max(1, blockTimeout.toMillis())));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return List.of();
            }
            return after(conversationId, after);
        }

        @Override
        public void signalCancellation(String runId) {
        }

        @Override
        public boolean isCancellationRequested(String runId) {
            return false;
        }

        private List<ConversationLiveEvent> after(String conversationId, long cursor) {
            return conversationEvents.getOrDefault(conversationId, List.of()).stream()
                    .filter(event -> event.sequence() > cursor)
                    .toList();
        }
    }
}
