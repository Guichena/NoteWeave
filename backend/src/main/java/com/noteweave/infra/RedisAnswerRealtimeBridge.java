package com.noteweave.infra;

import com.noteweave.answer.AnswerLiveEvent;
import com.noteweave.answer.AnswerRealtimeBridge;
import com.noteweave.answer.AnswerRealtimeBridgeUnavailableException;
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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import com.noteweave.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisAnswerRealtimeBridge implements AnswerRealtimeBridge {

    private static final String CANCELLED = "1";
    private static final Logger log = LoggerFactory.getLogger(RedisAnswerRealtimeBridge.class);

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

    private static final String APPEND_SCRIPT = """
            if redis.call('EXISTS',KEYS[1]) == 1 then return -1 end
            if redis.call('EXISTS',KEYS[2]) == 0 then
              local high=tonumber(redis.call('GET',KEYS[5]) or '0')
              local floor=tonumber(redis.call('GET',KEYS[4]) or '0')
              if high > floor then redis.call('SET',KEYS[4],high) end
            end
            local seq=tonumber(ARGV[1])
            if ARGV[7] == 'conversation' then
              local last=redis.call('XREVRANGE',KEYS[2],'+','-','COUNT',1)
              local floor=tonumber(redis.call('GET',KEYS[3]) or '0')
              if #last > 0 then
                local lastid=tonumber(string.match(last[1][1],'^(%d+)'))
                if lastid > floor then redis.call('SET',KEYS[3],lastid) end
              end
              seq=redis.call('INCR',KEYS[3])
              redis.call('PERSIST',KEYS[3])
            else
              local last=redis.call('XREVRANGE',KEYS[2],'+','-','COUNT',1)
              if #last > 0 and tonumber(string.match(last[1][1],'^(%d+)')) >= seq then return 0 end
            end
            redis.call('XADD',KEYS[2],seq..'-0','sequence',seq,'type',ARGV[2],'data',ARGV[3],
                       'occurred_at',ARGV[4],'run_id',ARGV[5],'run_sequence',ARGV[1])
            local excess=redis.call('XLEN',KEYS[2])-tonumber(ARGV[6])
            if excess > 0 then
              local removed=redis.call('XRANGE',KEYS[2],'-','+','COUNT',excess)
              for _,entry in ipairs(removed) do
                redis.call('XDEL',KEYS[2],entry[1])
                redis.call('SET',KEYS[4],string.match(entry[1],'^(%d+)'))
              end
            end
            redis.call('EXPIRE',KEYS[2],ARGV[8])
            redis.call('SET',KEYS[5],seq)
            return seq
            """;
    private static final DefaultRedisScript<Long> APPEND = new DefaultRedisScript<>(APPEND_SCRIPT,Long.class);
    private static final DefaultRedisScript<Long> REDACT = new DefaultRedisScript<>("""
            redis.call('SET',KEYS[1],'1')
            redis.call('SET',KEYS[2],'1')
            redis.call('DEL',KEYS[3])
            local entries=redis.call('XRANGE',KEYS[4],'-','+')
            local count=0
            for _,entry in ipairs(entries) do
              local fields=entry[2]
              for i=1,#fields,2 do
                if fields[i]=='run_id' and fields[i+1]==ARGV[1] then
                  count=count+redis.call('XDEL',KEYS[4],entry[1]); break
                end
              end
            end
            return count
            """,Long.class);

    @Override
    public void publish(String runId, AnswerLiveEvent event) {
        append(runId,null,event);
    }

    private long append(String runId,String conversationId,AnswerLiveEvent event) {
        String key=conversationId==null?streamKey(runId):conversationStreamKey(conversationId);
        try {
            Long seq=redisTemplate.execute(APPEND,List.of(revokedKey(runId),key,
                    conversationId==null?key+":unused":conversationSequenceKey(conversationId),key+":trimmed",key+":highwater"),
                    Long.toString(event.sequence()),event.eventType(),event.data(),event.occurredAt().toString(),runId,
                    Integer.toString(maxLength),conversationId==null?"run":"conversation",Long.toString(ttl.toSeconds()));
            if(seq==null) throw new IllegalStateException("Redis未返回事件序号");
            if(seq<0) throw new BusinessException("ANSWER_REPLAY_REVOKED","回答回放已撤销",HttpStatus.CONFLICT);
            return seq;
        } catch (BusinessException ex) { throw ex; }
        catch (RuntimeException ex) { throw unavailable("atomic publish",ex); }
    }

    @Override
    public void redact(String conversationId,String runId) {
        try {
            redisTemplate.execute(REDACT,List.of(revokedKey(runId),cancelKey(runId),streamKey(runId),conversationStreamKey(conversationId)),runId);
        } catch(RuntimeException ex) { throw unavailable("redact",ex); }
    }

    @Override
    public boolean isReplayRevoked(String runId) {
        try { return Boolean.TRUE.equals(redisTemplate.hasKey(revokedKey(runId))); }
        catch(RuntimeException ex) { throw unavailable("replay revocation",ex); }
    }

    private void requireWindow(String key,long after,String code) {
        String floor=redisTemplate.opsForValue().get(key+":trimmed");
        String high=redisTemplate.opsForValue().get(key+":highwater");
        boolean expired=floor!=null && after<Long.parseLong(floor);
        if(!Boolean.TRUE.equals(redisTemplate.hasKey(key)) && high!=null && after<Long.parseLong(high)) expired=true;
        if(expired) throw new BusinessException(code,"事件正文已超出恢复窗口，需等待完整完成快照",HttpStatus.CONFLICT);
    }

    private StreamReadOptions readOptions(Duration timeout) {
        StreamReadOptions options=StreamReadOptions.empty().count(maxLength+1);
        return timeout==null || timeout.isZero() || timeout.isNegative()?options:options.block(timeout);
    }

    @Override
    public List<AnswerLiveEvent> readAfter(String runId, long after, Duration blockTimeout) {
        try {
            if(isReplayRevoked(runId)) return List.of();
            requireWindow(streamKey(runId),after,"ANSWER_EVENT_CURSOR_EXPIRED");
            var records = redisTemplate.opsForStream().read(
                    readOptions(blockTimeout),
                    StreamOffset.create(streamKey(runId), ReadOffset.from(Math.max(0, after) + "-0"))
            );
            if (records == null || records.isEmpty()) {
                return List.of();
            }
            requireWindow(streamKey(runId),after,"ANSWER_EVENT_CURSOR_EXPIRED");
            if(isReplayRevoked(runId)) return List.of();
            meterRegistry.counter("noteweave.answer.redis.read").increment(records.size());
            return records.stream().map(this::toEvent).toList();
        } catch (BusinessException ex) { throw ex; }
        catch (RuntimeException ex) {
            meterRegistry.counter("noteweave.answer.redis.error", "operation", "read").increment();
            throw unavailable("answer read", ex);
        }
    }

    @Override
    public ConversationLiveEvent publishConversation(
            String conversationId,
            String runId,
            AnswerLiveEvent event
    ) {
        RuntimeException lastFailure = null;
        // Redis can briefly reject a publish while the connection is reconnecting (for
        // example immediately after Kafka/Redis recovery). A single bounded retry keeps
        // that transient blip from turning an otherwise healthy answer into a failed
        // conversation turn. Sequence numbers are allocated per attempt, so retries
        // remain monotonic and do not overwrite an already-published stream record.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return publishConversationOnce(conversationId, runId, event);
            } catch (BusinessException ex) { throw ex; }
            catch (RuntimeException ex) {
                lastFailure = ex;
                if (attempt == 0) {
                    try {
                        Thread.sleep(40L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        meterRegistry.counter("noteweave.conversation.redis.error", "operation", "publish").increment();
        throw unavailable("conversation publish", lastFailure);
    }

    private ConversationLiveEvent publishConversationOnce(String conversationId,String runId,AnswerLiveEvent event) {
        long sequence=append(runId,conversationId,event);
        return new ConversationLiveEvent(sequence,runId,event.sequence(),event.eventType(),event.data(),event.occurredAt());
    }

    @Override
    public List<ConversationLiveEvent> readConversationAfter(
            String conversationId,
            long after,
            Duration blockTimeout
    ) {
        try {
            requireWindow(conversationStreamKey(conversationId),after,"CONVERSATION_EVENT_CURSOR_EXPIRED");
            var records = redisTemplate.opsForStream().read(
                    readOptions(blockTimeout),
                    StreamOffset.create(
                            conversationStreamKey(conversationId),
                            ReadOffset.from(Math.max(0, after) + "-0"))
            );
            if (records == null || records.isEmpty()) {
                return List.of();
            }
            requireWindow(conversationStreamKey(conversationId),after,"CONVERSATION_EVENT_CURSOR_EXPIRED");
            meterRegistry.counter("noteweave.conversation.redis.read").increment(records.size());
            return records.stream().map(this::toConversationEvent).filter(event -> !isReplayRevoked(event.runId())).toList();
        } catch (BusinessException ex) { throw ex; }
        catch (RuntimeException ex) {
            meterRegistry.counter("noteweave.conversation.redis.error", "operation", "read").increment();
            throw unavailable("conversation read", ex);
        }
    }

    @Override
    public void signalCancellation(String runId) {
        try {
            redisTemplate.opsForValue().set(cancelKey(runId), CANCELLED, ttl);
            meterRegistry.counter("noteweave.answer.redis.cancel.publish").increment();
        } catch (RuntimeException ex) {
            meterRegistry.counter("noteweave.answer.redis.error", "operation", "cancel_publish").increment();
            log.error("Redis cancellation publish failed for answer run {}; DB cancellation remains authoritative", runId, ex);
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
            log.error("Redis cancellation read failed for answer run {}; failing closed", runId, ex);
            return true;
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

    private String revokedKey(String runId) { return "noteweave:v1:revoked:answer:"+runId; }

    private String streamKey(String runId) {
        return "noteweave:v1:stream:answer:" + runId;
    }

    private String cancelKey(String runId) {
        return "noteweave:v1:cancel:answer:" + runId;
    }

    private String conversationStreamKey(String conversationId) {
        return "noteweave:v1:stream:conversation:" + conversationId;
    }

    private String conversationSequenceKey(String conversationId) {
        return "noteweave:v1:seq:conversation:" + conversationId;
    }

    private AnswerRealtimeBridgeUnavailableException unavailable(String operation, RuntimeException cause) {
        return new AnswerRealtimeBridgeUnavailableException(operation, cause);
    }
}
