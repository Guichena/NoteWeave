package com.noteweave.answer;

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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Conversation-wide event stream. It aggregates events from multiple answer
 * runs while preserving both a conversation cursor and the original run cursor.
 */
@Component
public class ConversationEventMux {

    private final Map<String, Long> sequenceHighWater = new ConcurrentHashMap<>();
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final AnswerRealtimeBridge bridge;
    private final Executor bridgeExecutor;
    private final Executor dispatchExecutor;
    private final MeterRegistry meterRegistry;
    private final int replayCapacity;
    private final int subscriberQueueCapacity;
    private final Duration channelRetention;
    private final int maxRetainedChannels;
    private AnswerReplayAccessGuard replayAccessGuard;
    private final java.util.Set<String> revokedRuns = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeSubscribers = new AtomicInteger();

    @Autowired
    public ConversationEventMux(
            ObjectProvider<AnswerRealtimeBridge> bridgeProvider,
            @Qualifier("answerBridgeExecutor") Executor bridgeExecutor,
            @Qualifier("sseDispatchExecutor") Executor dispatchExecutor,
            MeterRegistry meterRegistry,
            AnswerReplayAccessGuard replayAccessGuard,
            @Value("${noteweave.conversation.events.replay-capacity:512}") int replayCapacity,
            @Value("${noteweave.conversation.events.subscriber-queue-capacity:64}") int subscriberQueueCapacity,
            @Value("${noteweave.conversation.events.channel-retention-seconds:900}") long channelRetentionSeconds,
            @Value("${noteweave.conversation.events.max-retained-channels:10000}") int maxRetainedChannels
    ) {
        this.bridge = bridgeProvider.getIfAvailable();
        this.bridgeExecutor = bridgeExecutor;
        this.dispatchExecutor = dispatchExecutor;
        this.meterRegistry = meterRegistry;
        this.replayAccessGuard = java.util.Objects.requireNonNull(replayAccessGuard);
        this.replayCapacity = Math.max(32, replayCapacity);
        this.subscriberQueueCapacity = Math.max(8, subscriberQueueCapacity);
        this.channelRetention = Duration.ofSeconds(Math.max(1, channelRetentionSeconds));
        this.maxRetainedChannels = Math.max(128, maxRetainedChannels);
        meterRegistry.gauge("noteweave.conversation.events.active_subscribers", activeSubscribers);
    }

    ConversationEventMux(
            AnswerRealtimeBridge bridge,
            Executor bridgeExecutor,
            Executor dispatchExecutor,
            MeterRegistry meterRegistry,
            int replayCapacity,
            int subscriberQueueCapacity
    ) {
        this(
                bridge,
                bridgeExecutor,
                dispatchExecutor,
                meterRegistry,
                replayCapacity,
                subscriberQueueCapacity,
                Duration.ofMinutes(15),
                10_000
        );
    }

    ConversationEventMux(
            AnswerRealtimeBridge bridge,
            Executor bridgeExecutor,
            Executor dispatchExecutor,
            MeterRegistry meterRegistry,
            int replayCapacity,
            int subscriberQueueCapacity,
            Duration channelRetention,
            int maxRetainedChannels
    ) {
        this.bridge = bridge;
        this.bridgeExecutor = bridgeExecutor;
        this.dispatchExecutor = dispatchExecutor;
        this.meterRegistry = meterRegistry;
        this.replayCapacity = Math.max(32, replayCapacity);
        this.subscriberQueueCapacity = Math.max(8, subscriberQueueCapacity);
        this.channelRetention = channelRetention == null || channelRetention.isNegative()
                ? Duration.ofMinutes(15)
                : channelRetention;
        this.maxRetainedChannels = Math.max(1, maxRetainedChannels);
        meterRegistry.gauge("noteweave.conversation.events.active_subscribers", activeSubscribers);
    }

    public ConversationLiveEvent publish(String conversationId, String runId, AnswerLiveEvent runEvent) {
        requireReadable(runId);
        cleanupIdleChannels();
        Channel channel = channels.computeIfAbsent(conversationId, ignored -> newChannel(conversationId));
        ConversationLiveEvent event = publishToBridge(conversationId, runId, runEvent);
        List<Subscriber> subscribers;
        synchronized (channel) {
            requireReadable(runId);
            if (event == null) {
                event = new ConversationLiveEvent(
                        ++channel.sequence,
                        runId,
                        runEvent.sequence(),
                        runEvent.eventType(),
                        runEvent.data(),
                        runEvent.occurredAt()
                );
            }
            if (event.sequence() <= channel.sequence && contains(channel, event.sequence())) {
                return event;
            }
            channel.sequence = Math.max(channel.sequence, event.sequence());
            channel.lastTouchedAt = Instant.now();
            append(channel, event);
            sequenceHighWater.merge(conversationId,channel.sequence,Math::max);
            subscribers = new ArrayList<>(channel.subscribers);
        }
        meterRegistry.counter(
                "noteweave.conversation.events.published", "type", event.eventType()).increment();
        ConversationLiveEvent delivered = event;
        subscribers.forEach(subscriber -> subscriber.enqueue(delivered));
        return event;
    }

    public void redact(String conversationId, String runId) {
        revokedRuns.add(runId);
        Channel channel = channels.get(conversationId);
        if (channel == null) return;
        synchronized (channel) {
            channel.replay.removeIf(event -> runId.equals(event.runId()));
            // 关闭现有订阅清除已排队副本，客户端用快照恢复其他运行。
        }
        List<Subscriber> subscribers;
        synchronized (channel) { subscribers = new ArrayList<>(channel.subscribers); }
        subscribers.forEach(Subscriber::close);
    }

    public boolean hasSubscribers(String conversationId) {
        cleanupIdleChannels();
        Channel channel = channels.get(conversationId);
        if (channel == null) {
            return false;
        }
        synchronized (channel) {
            channel.lastTouchedAt = java.time.Instant.now();
            return !channel.subscribers.isEmpty();
        }
    }

    public void follow(
            String conversationId,
            long after,
            Consumer<ConversationLiveEvent> consumer,
            Duration timeout,
            boolean closeOnAnswerTerminal
    ) {
        cleanupIdleChannels();
        Channel channel = channels.computeIfAbsent(conversationId, ignored -> newChannel(conversationId));
        List<ConversationLiveEvent> remote = bridge == null ? List.of()
                : bridge.readConversationAfter(conversationId,after,Duration.ZERO);
        Subscriber subscriber = new Subscriber(channel, consumer, closeOnAnswerTerminal);
        List<ConversationLiveEvent> replay;
        synchronized (channel) {
            channel.lastTouchedAt = java.time.Instant.now();
            if (bridge == null && after < channel.evictedThrough) throw new com.noteweave.common.BusinessException(
                    "CONVERSATION_EVENT_CURSOR_EXPIRED", "事件正文不完整，需要完成快照恢复", org.springframework.http.HttpStatus.CONFLICT);
            java.util.TreeMap<Long,ConversationLiveEvent> merged = new java.util.TreeMap<>();
            remote.stream().filter(event -> event.sequence()>after).forEach(event -> merged.put(event.sequence(),event));
            channel.replay.stream().filter(event -> event.sequence()>after).forEach(event -> merged.put(event.sequence(),event));
            replay = new ArrayList<>(merged.values());
            subscriber.lastSequence = after;
            channel.subscribers.add(subscriber);
            activeSubscribers.incrementAndGet();
            subscriber.prime(replay);
        }
        subscriber.startDrain();
        ensureBridgePump(conversationId, channel, Math.max(after, currentSequence(channel)));
        try {
            subscriber.await(timeout);
            if (subscriber.failure != null) throw subscriber.failure;
        } finally {
            subscriber.close();
        }
    }

    private boolean readable(String runId) {
        if (revokedRuns.contains(runId)) return false;
        if (replayAccessGuard != null && !replayAccessGuard.isReadable(runId)) return false;
        return bridge == null || !bridge.isReplayRevoked(runId);
    }
    private void requireReadable(String runId) {
        if (!readable(runId)) throw new com.noteweave.common.BusinessException("ANSWER_REPLAY_REVOKED", "回答回放已撤销", org.springframework.http.HttpStatus.CONFLICT);
    }
    /** 新通道从高水位续号，避免清理后重新从 1 开始。 */
    private Channel newChannel(String conversationId) {
        Channel created = new Channel();
        created.sequence = sequenceHighWater.getOrDefault(conversationId, 0L);
        created.evictedThrough = created.sequence;
        return created;
    }

    private void redactEvent(Channel channel,String runId) {
        revokedRuns.add(runId);
        synchronized (channel) { channel.replay.removeIf(event -> runId.equals(event.runId())); }
    }

    private void ensureBridgePump(String conversationId, Channel channel, long after) {
        if (bridge == null) {
            return;
        }
        synchronized (channel) {
            if (channel.bridgePumpRunning) {
                return;
            }
            channel.bridgePumpRunning = true;
        }
        try {
            bridgeExecutor.execute(() -> pump(conversationId, channel, after));
        } catch (RejectedExecutionException ex) {
            synchronized (channel) {
                channel.bridgePumpRunning = false;
            }
            meterRegistry.counter("noteweave.conversation.events.bridge_rejected").increment();
        }
    }

    private void pump(String conversationId, Channel channel, long after) {
        long cursor = after;
        try {
            while (true) {
                synchronized (channel) {
                    if (channel.subscribers.isEmpty()) {
                        return;
                    }
                }
                List<ConversationLiveEvent> events;
                try {
                    events = bridge.readConversationAfter(conversationId, cursor, Duration.ofSeconds(1));
                } catch (AnswerRealtimeBridgeUnavailableException ex) {
                    meterRegistry.counter("noteweave.conversation.events.bridge_unavailable", "operation", "read")
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
                for (ConversationLiveEvent event : events) {
                    cursor = Math.max(cursor, event.sequence());
                    acceptRemote(conversationId,channel, event);
                }
            }
        } catch (com.noteweave.common.BusinessException ex) {
            List<Subscriber> affected;
            synchronized (channel) { affected = new ArrayList<>(channel.subscribers); }
            for (Subscriber subscriber : affected) { subscriber.failure = ex; subscriber.close(); }
        } finally {
            synchronized (channel) {
                channel.bridgePumpRunning = false;
            }
        }
    }

    private ConversationLiveEvent publishToBridge(
            String conversationId,
            String runId,
            AnswerLiveEvent runEvent
    ) {
        if (bridge == null) {
            return null;
        }
        try {
            return bridge.publishConversation(conversationId, runId, runEvent);
        } catch (AnswerRealtimeBridgeUnavailableException ex) {
            meterRegistry.counter("noteweave.conversation.events.bridge_unavailable", "operation", "publish")
                    .increment();
            throw ex;
        }
    }

    private void acceptRemote(String conversationId,Channel channel, ConversationLiveEvent event) {
        List<Subscriber> subscribers;
        synchronized (channel) {
            if (!readable(event.runId())) return;
            if (event.sequence() <= channel.sequence && contains(channel, event.sequence())) {
                return;
            }
            channel.sequence = Math.max(channel.sequence, event.sequence());
            channel.lastTouchedAt = Instant.now();
            append(channel, event);
            sequenceHighWater.merge(conversationId,channel.sequence,Math::max);
            subscribers = new ArrayList<>(channel.subscribers);
        }
        meterRegistry.counter(
                "noteweave.conversation.events.remote_delivered", "type", event.eventType()).increment();
        subscribers.forEach(subscriber -> subscriber.enqueue(event));
    }

    private void append(Channel channel, ConversationLiveEvent event) {
        channel.replay.addLast(event);
        while (channel.replay.size() > replayCapacity) {
            channel.evictedThrough = Math.max(channel.evictedThrough,channel.replay.removeFirst().sequence());
            meterRegistry.counter("noteweave.conversation.events.replay_trimmed").increment();
        }
    }

    private boolean contains(Channel channel, long sequence) {
        return channel.replay.stream().anyMatch(event -> event.sequence() == sequence);
    }

    private long currentSequence(Channel channel) {
        synchronized (channel) {
            return channel.sequence;
        }
    }

    private void cleanupIdleChannels() {
        Instant cutoff = Instant.now().minus(channelRetention);
        channels.entrySet().removeIf(entry -> {
            Channel channel = entry.getValue();
            synchronized (channel) {
                return channel.subscribers.isEmpty()
                        && channel.lastTouchedAt != null
                        && channel.lastTouchedAt.isBefore(cutoff);
            }
        });
        if (channels.size() <= maxRetainedChannels) {
            return;
        }
        channels.entrySet().stream()
                .filter(entry -> {
                    Channel channel = entry.getValue();
                    synchronized (channel) {
                        return channel.subscribers.isEmpty() && channel.lastTouchedAt != null;
                    }
                })
                .sorted((left, right) -> left.getValue().lastTouchedAt.compareTo(right.getValue().lastTouchedAt))
                .limit(Math.max(0, channels.size() - maxRetainedChannels))
                .forEach(entry -> channels.remove(entry.getKey(), entry.getValue()));
    }

    private boolean terminal(String eventType) {
        return "answer.completed".equals(eventType)
                || "answer.failed".equals(eventType)
                || "answer.cancelled".equals(eventType);
    }

    private final class Subscriber {
        private final Channel channel;
        private final Consumer<ConversationLiveEvent> consumer;
        private final boolean closeOnAnswerTerminal;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final Deque<ConversationLiveEvent> pending = new ArrayDeque<>();
        private long lastSequence;
        private RuntimeException failure;
        private boolean draining;
        private boolean closed;

        private Subscriber(
                Channel channel,
                Consumer<ConversationLiveEvent> consumer,
                boolean closeOnAnswerTerminal
        ) {
            this.channel = channel;
            this.consumer = consumer;
            this.closeOnAnswerTerminal = closeOnAnswerTerminal;
        }

        private void enqueue(ConversationLiveEvent event) {
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

        private void prime(List<ConversationLiveEvent> events) {
            synchronized (this) {
                for (ConversationLiveEvent event : events) {
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

        private boolean appendPendingLocked(ConversationLiveEvent event) {
            if (pending.size() >= subscriberQueueCapacity) {
                ConversationLiveEvent last = pending.peekLast();
                if (last != null
                        && "answer.delta".equals(last.eventType())
                        && "answer.delta".equals(event.eventType())
                        && last.runId().equals(event.runId())) {
                    pending.removeLast();
                    pending.addLast(new ConversationLiveEvent(
                            event.sequence(),
                            event.runId(),
                            event.runSequence(),
                            event.eventType(),
                            last.data() + event.data(),
                            event.occurredAt()
                    ));
                    meterRegistry.counter("noteweave.conversation.events.delta_coalesced").increment();
                    return true;
                }
                meterRegistry.counter("noteweave.conversation.events.subscriber_overflow").increment();
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
                meterRegistry.counter("noteweave.conversation.events.dispatch_rejected").increment();
                close();
            }
        }

        private void drain() {
            while (true) {
                ConversationLiveEvent event;
                synchronized (this) {
                    event = pending.pollFirst();
                    if (event == null || closed) {
                        draining = false;
                        return;
                    }
                }
                try {
                    if (!readable(event.runId())) { redactEvent(channel,event.runId()); continue; }
                    if (event.sequence() <= lastSequence) continue;
                    consumer.accept(event);
                    lastSequence = event.sequence();
                } catch (RuntimeException ex) {
                    close();
                    return;
                }
                if (closeOnAnswerTerminal && terminal(event.eventType())) {
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

    private final class Channel {
        private long sequence;
        private long evictedThrough;
        private boolean bridgePumpRunning;
        private Instant lastTouchedAt = Instant.now();
        private final Deque<ConversationLiveEvent> replay = new ArrayDeque<>();
        private final List<Subscriber> subscribers = new ArrayList<>();
    }
}
