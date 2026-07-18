package com.noteweave.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class SessionEventMuxTest {

    @Test
    void shouldPrimeReplayBeforeLiveTerminalCanCloseSubscriber() throws Exception {
        CountDownLatch bridgeEntered = new CountDownLatch(1);
        CountDownLatch releaseBridge = new CountDownLatch(1);
        java.util.concurrent.Executor blockingBridgeExecutor = ignored -> {
            bridgeEntered.countDown();
            try {
                releaseBridge.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };
        SessionEventMux mux = new SessionEventMux(
                Runnable::run,
                blockingBridgeExecutor,
                new InMemoryBridge(),
                new SimpleMeterRegistry(),
                64,
                16
        );
        mux.seed("run-replay-race", 4);
        mux.publish("run-replay-race", "answer.delta", "replayed");
        List<AnswerLiveEvent> received = new CopyOnWriteArrayList<>();
        Thread follower = new Thread(() -> mux.follow(
                "run-replay-race", 4, received::add, Duration.ofSeconds(2)));

        follower.start();
        assertThat(bridgeEntered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        mux.publish("run-replay-race", "citation.upsert", "citation");
        mux.publish("run-replay-race", "answer.completed", "message");
        releaseBridge.countDown();
        follower.join(2000);

        assertThat(follower.isAlive()).isFalse();
        assertThat(received).extracting(AnswerLiveEvent::sequence)
                .containsExactly(5L, 6L, 7L);
        assertThat(received).extracting(AnswerLiveEvent::eventType)
                .containsExactly("answer.delta", "citation.upsert", "answer.completed");
    }

    @Test
    void shouldReplayAfterCursorAndContinueUntilTerminalInOrder() throws Exception {
        SessionEventMux mux = new SessionEventMux(Runnable::run, new SimpleMeterRegistry(), 64, 16);
        mux.seed("run-1", 4);
        mux.publish("run-1", "answer.delta", "A");
        List<AnswerLiveEvent> received = new CopyOnWriteArrayList<>();

        Thread follower = new Thread(() -> mux.follow(
                "run-1", 4, received::add, Duration.ofSeconds(2)));
        follower.start();
        while (received.isEmpty()) {
            Thread.onSpinWait();
        }
        mux.publish("run-1", "citation.upsert", "citation");
        mux.publishAt("run-1", 9, "answer.completed", "message-1");
        follower.join(2000);

        assertThat(follower.isAlive()).isFalse();
        assertThat(received).extracting(AnswerLiveEvent::sequence).containsExactly(5L, 6L, 9L);
        assertThat(received).extracting(AnswerLiveEvent::eventType)
                .containsExactly("answer.delta", "citation.upsert", "answer.completed");
    }

    @Test
    void shouldRejectCursorOlderThanBoundedReplayWindow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionEventMux mux = new SessionEventMux(Runnable::run, registry, 32, 8);
        mux.seed("run-2", 4);
        for (int i = 0; i < 40; i++) {
            mux.publish("run-2", "answer.delta", Integer.toString(i));
        }

        assertThatThrownBy(() -> mux.follow("run-2", 4, ignored -> { }, Duration.ofMillis(10)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code())
                .isEqualTo("ANSWER_EVENT_CURSOR_EXPIRED");
        assertThat(registry.get("noteweave.answer.events.cursor_expired").counter().count()).isEqualTo(1.0);
    }

    @Test
    void deltaEmitterShouldCoalesceTokensBeforePublishing() {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            List<String> batches = new CopyOnWriteArrayList<>();
            try (BufferedAnswerDeltaEmitter emitter = new BufferedAnswerDeltaEmitter(scheduler, batches::add)) {
                emitter.accept("hello");
                emitter.accept(" ");
                emitter.accept("world");
            }
            assertThat(batches).containsExactly("hello world");
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void separateMuxInstancesShouldFollowTheSameBridgeWithoutDuplicateGeneration() throws Exception {
        InMemoryBridge bridge = new InMemoryBridge();
        var bridgeExecutor = Executors.newSingleThreadExecutor();
        try {
            SessionEventMux owner = new SessionEventMux(
                    Runnable::run, bridgeExecutor, bridge, new SimpleMeterRegistry(), 64, 16);
            SessionEventMux follower = new SessionEventMux(
                    Runnable::run, bridgeExecutor, bridge, new SimpleMeterRegistry(), 64, 16);
            owner.seed("run-shared", 4);
            follower.seed("run-shared", 4);
            List<AnswerLiveEvent> received = new CopyOnWriteArrayList<>();
            Thread followThread = new Thread(() -> follower.follow(
                    "run-shared", 4, received::add, Duration.ofSeconds(2)));
            followThread.start();

            owner.publish("run-shared", "answer.delta", "remote-delta");
            owner.publishAt("run-shared", 8, "answer.completed", "message-1");
            followThread.join(2000);

            assertThat(followThread.isAlive()).isFalse();
            assertThat(received).extracting(AnswerLiveEvent::sequence).containsExactly(5L, 8L);
            assertThat(received).extracting(AnswerLiveEvent::eventType)
                    .containsExactly("answer.delta", "answer.completed");
        } finally {
            bridgeExecutor.shutdownNow();
        }
    }

    private static final class InMemoryBridge implements AnswerRealtimeBridge {
        private final Map<String, List<AnswerLiveEvent>> events = new ConcurrentHashMap<>();
        private final java.util.Set<String> cancellations = ConcurrentHashMap.newKeySet();

        @Override
        public synchronized void publish(String runId, AnswerLiveEvent event) {
            events.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(event);
            notifyAll();
        }

        @Override
        public synchronized List<AnswerLiveEvent> readAfter(String runId, long after, Duration blockTimeout) {
            List<AnswerLiveEvent> available = after(runId, after);
            if (!available.isEmpty()) {
                return available;
            }
            try {
                wait(Math.min(100, Math.max(1, blockTimeout.toMillis())));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return List.of();
            }
            return after(runId, after);
        }

        @Override
        public void signalCancellation(String runId) {
            cancellations.add(runId);
        }

        @Override
        public boolean isCancellationRequested(String runId) {
            return cancellations.contains(runId);
        }

        private List<AnswerLiveEvent> after(String runId, long cursor) {
            return events.getOrDefault(runId, List.of()).stream()
                    .filter(event -> event.sequence() > cursor)
                    .toList();
        }
    }
}
