package com.noteweave.infra;

import com.noteweave.answer.AnswerLiveEvent;
import com.noteweave.answer.AnswerRealtimeBridge;
import com.noteweave.answer.ConversationLiveEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisAnswerRealtimeBridge implements AnswerRealtimeBridge {

    private static final String CANCELLED = "1";

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final int maxLength;
    private final Duration ttl;

    public RedisAnswerRealtimeBridge(
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            @Value("${noteweave.answer.redis-stream.max-length:512}") int maxLength,
            @Value("${noteweave.answer.redis-stream.ttl-seconds:600}") long ttlSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.maxLength = Math.max(64, maxLength);
        this.ttl = Duration.ofSeconds(Math.max(60, ttlSeconds));
    }

    @Override
    public void publish(String runId, AnswerLiveEvent event) {
        String key = streamKey(runId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("sequence", Long.toString(event.sequence()));
        fields.put("type", event.eventType());
        fields.put("data", event.data());
        fields.put("occurred_at", event.occurredAt().toString());
        try {
            MapRecord<String, String, String> record = StreamRecords.string(fields)
                    .withStreamKey(key)
                    .withId(RecordId.of(event.sequence() + "-0"));
            redisTemplate.opsForStream().add(record);
            redisTemplate.opsForStream().trim(key, maxLength, true);
            redisTemplate.expire(key, ttl);
            meterRegistry.counter("noteweave.answer.redis.publish", "type", event.eventType()).increment();
        } catch (RuntimeException ex) {
            // A duplicate custom ID means this event is already bridged; other errors degrade to local mux.
            String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            meterRegistry.counter(
                    message.contains("equal or smaller")
                            ? "noteweave.answer.redis.duplicate"
                            : "noteweave.answer.redis.error",
                    "operation",
                    "publish"
            ).increment();
        }
    }

    @Override
    public List<AnswerLiveEvent> readAfter(String runId, long after, Duration blockTimeout) {
        try {
            var records = redisTemplate.opsForStream().read(
                    StreamReadOptions.empty().count(64).block(blockTimeout),
                    StreamOffset.create(streamKey(runId), ReadOffset.from(Math.max(0, after) + "-0"))
            );
            if (records == null || records.isEmpty()) {
                return List.of();
            }
            meterRegistry.counter("noteweave.answer.redis.read").increment(records.size());
            return records.stream().map(this::toEvent).toList();
        } catch (RuntimeException ex) {
            meterRegistry.counter("noteweave.answer.redis.error", "operation", "read").increment();
            return List.of();
        }
    }

    @Override
    public ConversationLiveEvent publishConversation(
            String conversationId,
            String runId,
            AnswerLiveEvent event
    ) {
        String streamKey = conversationStreamKey(conversationId);
        String sequenceKey = conversationSequenceKey(conversationId);
        try {
            Long sequence = redisTemplate.opsForValue().increment(sequenceKey);
            if (sequence == null) {
                return null;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("sequence", Long.toString(sequence));
            fields.put("run_id", runId);
            fields.put("run_sequence", Long.toString(event.sequence()));
            fields.put("type", event.eventType());
            fields.put("data", event.data());
            fields.put("occurred_at", event.occurredAt().toString());
            redisTemplate.opsForStream().add(StreamRecords.string(fields)
                    .withStreamKey(streamKey)
                    .withId(RecordId.of(sequence + "-0")));
            redisTemplate.opsForStream().trim(streamKey, maxLength, true);
            redisTemplate.expire(streamKey, ttl);
            redisTemplate.expire(sequenceKey, ttl);
            meterRegistry.counter("noteweave.conversation.redis.publish", "type", event.eventType()).increment();
            return new ConversationLiveEvent(
                    sequence, runId, event.sequence(), event.eventType(), event.data(), event.occurredAt());
        } catch (RuntimeException ex) {
            meterRegistry.counter("noteweave.conversation.redis.error", "operation", "publish").increment();
            return null;
        }
    }

    @Override
    public List<ConversationLiveEvent> readConversationAfter(
            String conversationId,
            long after,
            Duration blockTimeout
    ) {
        try {
            var records = redisTemplate.opsForStream().read(
                    StreamReadOptions.empty().count(64).block(blockTimeout),
                    StreamOffset.create(
                            conversationStreamKey(conversationId),
                            ReadOffset.from(Math.max(0, after) + "-0"))
            );
            if (records == null || records.isEmpty()) {
                return List.of();
            }
            meterRegistry.counter("noteweave.conversation.redis.read").increment(records.size());
            return records.stream().map(this::toConversationEvent).toList();
        } catch (RuntimeException ex) {
            meterRegistry.counter("noteweave.conversation.redis.error", "operation", "read").increment();
            return List.of();
        }
    }

    @Override
    public void signalCancellation(String runId) {
        try {
            redisTemplate.opsForValue().set(cancelKey(runId), CANCELLED, ttl);
            meterRegistry.counter("noteweave.answer.redis.cancel.publish").increment();
        } catch (RuntimeException ex) {
            meterRegistry.counter("noteweave.answer.redis.error", "operation", "cancel_publish").increment();
        }
    }

    @Override
    public boolean isCancellationRequested(String runId) {
        try {
            boolean cancelled = CANCELLED.equals(redisTemplate.opsForValue().get(cancelKey(runId)));
            if (cancelled) {
                meterRegistry.counter("noteweave.answer.redis.cancel.hit").increment();
            }
            return cancelled;
        } catch (RuntimeException ex) {
            meterRegistry.counter("noteweave.answer.redis.error", "operation", "cancel_read").increment();
            return false;
        }
    }

    private AnswerLiveEvent toEvent(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        long sequence = Long.parseLong(String.valueOf(value.get("sequence")));
        String type = String.valueOf(value.get("type"));
        String data = String.valueOf(value.get("data"));
        Object occurredAt = value.get("occurred_at");
        Instant occurred = occurredAt == null ? Instant.now() : Instant.parse(String.valueOf(occurredAt));
        return new AnswerLiveEvent(sequence, type, data, occurred);
    }

    private ConversationLiveEvent toConversationEvent(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        Object occurredAt = value.get("occurred_at");
        return new ConversationLiveEvent(
                Long.parseLong(String.valueOf(value.get("sequence"))),
                String.valueOf(value.get("run_id")),
                Long.parseLong(String.valueOf(value.get("run_sequence"))),
                String.valueOf(value.get("type")),
                String.valueOf(value.get("data")),
                occurredAt == null ? Instant.now() : Instant.parse(String.valueOf(occurredAt))
        );
    }

    private String streamKey(String runId) {
        return "stream:answer:" + runId;
    }

    private String cancelKey(String runId) {
        return "cancel:answer:" + runId;
    }

    private String conversationStreamKey(String conversationId) {
        return "stream:conversation:" + conversationId;
    }

    private String conversationSequenceKey(String conversationId) {
        return "seq:conversation:" + conversationId;
    }
}
