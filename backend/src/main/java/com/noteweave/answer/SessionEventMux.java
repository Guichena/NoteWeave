package com.noteweave.answer;

import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Single-instance run event multiplexer with bounded replay and per-subscriber
 * serial queues. MySQL remains the final snapshot; Redis supplies the same
 * contract across instances in the next slice.
 */
@Component
public class SessionEventMux {

    private final Map<String, RunChannel> channels = new ConcurrentHashMap<>();
    private final Executor dispatchExecutor;
    private final MeterRegistry meterRegistry;
    private final int replayCapacity;
    private final int subscriberQueueCapacity;
    private final AnswerRealtimeBridge realtimeBridge;
    private final Executor bridgeExecutor;
    private final AtomicInteger activeSubscribers = new AtomicInteger();

    @Autowired
    public SessionEventMux(
            @Qualifier("sseDispatchExecutor") Executor dispatchExecutor,
            @Qualifier("answerBridgeExecutor") Executor bridgeExecutor,
            ObjectProvider<AnswerRealtimeBridge> bridgeProvider,
            MeterRegistry meterRegistry,
            @Value("${noteweave.answer.events.replay-capacity:512}") int replayCapacity,
            @Value("${noteweave.answer.events.subscriber-queue-capacity:64}") int subscriberQueueCapacity
    ) {
        this.dispatchExecutor = dispatchExecutor;
        this.bridgeExecutor = bridgeExecutor;
        this.realtimeBridge = bridgeProvider.getIfAvailable();
        this.meterRegistry = meterRegistry;
        this.replayCapacity = Math.max(32, replayCapacity);
        this.subscriberQueueCapacity = Math.max(8, subscriberQueueCapacity);
        meterRegistry.gauge("noteweave.answer.events.active_subscribers", activeSubscribers);
    }

    public void seed(String runId, long sequence) {
        RunChannel channel = channels.computeIfAbsent(runId, ignored -> new RunChannel());
        synchronized (channel) {
            channel.sequence = Math.max(channel.sequence, sequence);
        }
    }

    public AnswerLiveEvent publish(String runId, String eventType, String data) {
        RunChannel channel = channels.computeIfAbsent(runId, ignored -> new RunChannel());
        AnswerLiveEvent event;
        List<Subscriber> subscribers;
        synchronized (channel) {
            event = new AnswerLiveEvent(++channel.sequence, eventType, data == null ? "" : data, Instant.now());
            appendToReplay(channel, event);
            if (isTerminal(eventType)) {
                channel.terminal = true;
                channel.terminalAt = Instant.now();
            }
            subscribers = new ArrayList<>(channel.subscribers);
        }
        meterRegistry.counter("noteweave.answer.events.published", "type", eventType).increment();
        subscribers.forEach(subscriber -> subscriber.enqueue(event));
        if (realtimeBridge != null) {
            publishToBridge(runId, event);
        }
        return event;
    }

    public AnswerLiveEvent publishAt(String runId, long sequence, String eventType, String data) {
        RunChannel channel = channels.computeIfAbsent(runId, ignored -> new RunChannel());
        AnswerLiveEvent event;
        List<Subscriber> subscribers;
        synchronized (channel) {
            channel.sequence = Math.max(channel.sequence, sequence);
            event = new AnswerLiveEvent(sequence, eventType, data == null ? "" : data, Instant.now());
            appendToReplay(channel, event);
            if (isTerminal(eventType)) {
                channel.terminal = true;
                channel.terminalAt = Instant.now();
            }
            subscribers = new ArrayList<>(channel.subscribers);
        }
        meterRegistry.counter("noteweave.answer.events.published", "type", eventType).increment();
        subscribers.forEach(subscriber -> subscriber.enqueue(event));
        if (realtimeBridge != null) {
            publishToBridge(runId, event);
        }
        return event;
    }

    public long currentSequence(String runId) {
        RunChannel channel = channels.get(runId);
        if (channel == null) {
            return 0;
        }
        synchronized (channel) {
            return channel.sequence;
        }
    }

    public void follow(String runId, long after, Consumer<AnswerLiveEvent> consumer, Duration timeout) {
        RunChannel channel = channels.computeIfAbsent(runId, ignored -> new RunChannel());
        Subscriber subscriber = new Subscriber(channel, consumer);
        List<AnswerLiveEvent> replay;
        synchronized (channel) {
            long oldest = channel.replay.isEmpty() ? channel.sequence + 1 : channel.replay.peekFirst().sequence();
            if (after > 0 && after < oldest - 1) {
                meterRegistry.counter("noteweave.answer.events.cursor_expired").increment();
                throw new BusinessException(
                        "ANSWER_EVENT_CURSOR_EXPIRED",
                        "事件游标已超出单实例恢复窗口，请读取 AnswerRun snapshot 后重新订阅",
                        HttpStatus.CONFLICT
                );
            }
            replay = channel.replay.stream().filter(event -> event.sequence() > after).toList();
            channel.subscribers.add(subscriber);
            activeSubscribers.incrementAndGet();
            subscriber.prime(replay);
        }
        subscriber.startDrain();
        ensureBridgePump(runId, channel, after);
        meterRegistry.counter("noteweave.answer.events.replayed").increment(replay.size());
        try {
            subscriber.await(timeout);
        } finally {
            subscriber.close();
        }
    }

    SessionEventMux(
            Executor dispatchExecutor,
            MeterRegistry meterRegistry,
            int replayCapacity,
            int subscriberQueueCapacity
    ) {
        this.dispatchExecutor = dispatchExecutor;
        this.bridgeExecutor = Runnable::run;
        this.realtimeBridge = null;
        this.meterRegistry = meterRegistry;
        this.replayCapacity = Math.max(32, replayCapacity);
        this.subscriberQueueCapacity = Math.max(8, subscriberQueueCapacity);
        meterRegistry.gauge("noteweave.answer.events.active_subscribers", activeSubscribers);
    }

    SessionEventMux(
            Executor dispatchExecutor,
            Executor bridgeExecutor,
            AnswerRealtimeBridge realtimeBridge,
            MeterRegistry meterRegistry,
            int replayCapacity,
            int subscriberQueueCapacity
    ) {
        this.dispatchExecutor = dispatchExecutor;
        this.bridgeExecutor = bridgeExecutor;
        this.realtimeBridge = realtimeBridge;
        this.meterRegistry = meterRegistry;
        this.replayCapacity = Math.max(32, replayCapacity);
        this.subscriberQueueCapacity = Math.max(8, subscriberQueueCapacity);
        meterRegistry.gauge("noteweave.answer.events.active_subscribers", activeSubscribers);
    }

    private void ensureBridgePump(String runId, RunChannel channel, long after) {
        if (realtimeBridge == null) {
            return;
        }
        synchronized (channel) {
            if (channel.bridgePumpRunning || channel.terminal) {
                return;
            }
            channel.bridgePumpRunning = true;
        }
        try {
            bridgeExecutor.execute(() -> pumpBridge(runId, channel, after));
        } catch (RejectedExecutionException ex) {
            synchronized (channel) {
                channel.bridgePumpRunning = false;
            }
            meterRegistry.counter("noteweave.answer.redis.bridge_rejected").increment();
        }
    }

    private void pumpBridge(String runId, RunChannel channel, long after) {
        long cursor;
        synchronized (channel) {
            cursor = Math.max(after, channel.sequence);
        }
        try {
            while (true) {
                synchronized (channel) {
                    if (channel.terminal || channel.subscribers.isEmpty()) {
                        return;
                    }
                }
                List<AnswerLiveEvent> events;
                try {
                    events = realtimeBridge.readAfter(runId, cursor, Duration.ofSeconds(1));
                } catch (AnswerRealtimeBridgeUnavailableException ex) {
                    meterRegistry.counter("noteweave.answer.events.bridge_unavailable", "operation", "read")
                            .increment();
                    LockSupport.parkNanos(Duration.ofSeconds(1).toNanos());
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    continue;
                }
                if (events.isEmpty()) {
                    LockSupport.parkNanos(Duration.ofMillis(250).toNanos());
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                }
                for (AnswerLiveEvent event : events) {
                    cursor = Math.max(cursor, event.sequence());
                    acceptRemote(runId, channel, event);
                }
            }
        } finally {
            synchronized (channel) {
                channel.bridgePumpRunning = false;
            }
        }
    }

    private void publishToBridge(String runId, AnswerLiveEvent event) {
        try {
            realtimeBridge.publish(runId, event);
        } catch (AnswerRealtimeBridgeUnavailableException ex) {
            meterRegistry.counter("noteweave.answer.events.bridge_unavailable", "operation", "publish")
                    .increment();
        }
    }

    private void acceptRemote(String runId, RunChannel channel, AnswerLiveEvent event) {
        List<Subscriber> subscribers;
        synchronized (channel) {
            if (event.sequence() <= channel.sequence) {
                return;
            }
            channel.sequence = event.sequence();
            appendToReplay(channel, event);
            if (isTerminal(event.eventType())) {
                channel.terminal = true;
                channel.terminalAt = Instant.now();
            }
            subscribers = new ArrayList<>(channel.subscribers);
        }
        meterRegistry.counter("noteweave.answer.redis.bridge_delivered", "type", event.eventType()).increment();
        subscribers.forEach(subscriber -> subscriber.enqueue(event));
    }

    private void appendToReplay(RunChannel channel, AnswerLiveEvent event) {
        channel.replay.addLast(event);
        while (channel.replay.size() > replayCapacity) {
            channel.replay.removeFirst();
            meterRegistry.counter("noteweave.answer.events.replay_trimmed").increment();
        }
    }

    private boolean isTerminal(String eventType) {
        return "answer.completed".equals(eventType)
                || "answer.failed".equals(eventType)
                || "answer.cancelled".equals(eventType);
    }

    private final class Subscriber {
        private final RunChannel channel;
        private final Consumer<AnswerLiveEvent> consumer;
        private final Deque<AnswerLiveEvent> pending = new ArrayDeque<>();
        private final CountDownLatch finished = new CountDownLatch(1);
        private boolean draining;
        private boolean closed;

        private Subscriber(RunChannel channel, Consumer<AnswerLiveEvent> consumer) {
            this.channel = channel;
            this.consumer = consumer;
        }

        private void prime(List<AnswerLiveEvent> events) {
            synchronized (this) {
                for (AnswerLiveEvent event : events) {
                    if (!appendPendingLocked(event)) {
                        return;
                    }
                }
            }
        }

        private void startDrain() {
            synchronized (this) {
                if (closed || draining || pending.isEmpty()) {
                    return;
                }
                draining = true;
            }
            scheduleDrain();
        }

        private void enqueue(AnswerLiveEvent event) {
            boolean shouldSchedule = false;
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (!appendPendingLocked(event)) {
                    return;
                }
                if (!draining) {
                    draining = true;
                    shouldSchedule = true;
                }
            }
            if (shouldSchedule) {
                scheduleDrain();
            }
        }

        private boolean appendPendingLocked(AnswerLiveEvent event) {
            if (pending.size() >= subscriberQueueCapacity) {
                AnswerLiveEvent last = pending.peekLast();
                if (last != null && "answer.delta".equals(last.eventType())
                        && "answer.delta".equals(event.eventType())) {
                    pending.removeLast();
                    pending.addLast(new AnswerLiveEvent(
                            event.sequence(), "answer.delta", last.data() + event.data(), event.occurredAt()));
                    meterRegistry.counter("noteweave.answer.events.delta_coalesced").increment();
                    return true;
                }
                meterRegistry.counter("noteweave.answer.events.subscriber_overflow").increment();
                closeLocked();
                return false;
            }
            pending.addLast(event);
            return true;
        }

        private void scheduleDrain() {
            try {
                dispatchExecutor.execute(this::drain);
            } catch (RejectedExecutionException ex) {
                meterRegistry.counter("noteweave.answer.events.dispatch_rejected").increment();
                close();
            }
        }

        private void drain() {
            while (true) {
                AnswerLiveEvent event;
                synchronized (this) {
                    event = pending.pollFirst();
                    if (event == null || closed) {
                        draining = false;
                        return;
                    }
                }
                try {
                    consumer.accept(event);
                } catch (RuntimeException ex) {
                    close();
                    return;
                }
                if (isTerminal(event.eventType())) {
                    close();
                    return;
                }
            }
        }

        private void await(Duration timeout) {
            try {
                finished.await(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private void close() {
            synchronized (this) {
                closeLocked();
            }
        }

        private void closeLocked() {
            if (closed) {
                return;
            }
            closed = true;
            pending.clear();
            synchronized (channel) {
                channel.subscribers.remove(this);
            }
            activeSubscribers.decrementAndGet();
            finished.countDown();
        }
    }

    private final class RunChannel {
        private long sequence;
        private boolean terminal;
        private Instant terminalAt;
        private boolean bridgePumpRunning;
        private final Deque<AnswerLiveEvent> replay = new ArrayDeque<>();
        private final List<Subscriber> subscribers = new ArrayList<>();
    }
}
