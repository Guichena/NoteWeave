package com.noteweave.chat.runtime.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.model.ChatDraftStatus;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.model.ChatSessionKind;
import com.noteweave.chat.repository.ChatSessionRepository;
import com.noteweave.chat.runtime.protocol.ServerEventEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRuntimeStateStore {

    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatSessionRepository chatSessionRepository;

    public void writeRuntimeState(Long sessionId, RuntimeState state) {
        RuntimeState existing = read(runtimeKey(sessionId), RuntimeState.class).orElse(null);
        if (existing != null) {
            if (state.getLastSeq() == null) {
                state.setLastSeq(existing.getLastSeq());
            }
            if (state.getLastAckSeq() == null) {
                state.setLastAckSeq(existing.getLastAckSeq());
            }
            if (state.getStreamId() == null) {
                state.setStreamId(existing.getStreamId());
            }
            if (state.getRequestId() == null) {
                state.setRequestId(existing.getRequestId());
            }
        }
        writeJson(runtimeKey(sessionId), state);
    }

    public void writeShortTermContext(Long sessionId, ShortTermContext context) {
        writeJson(shortTermKey(sessionId), context);
    }

    public void writeStreamState(Long sessionId, StreamState state) {
        writeJson(streamKey(sessionId), state);
    }

    public ServerEventEnvelope appendEvent(Long sessionId, ServerEventEnvelope envelope) {
        RuntimeState state = read(runtimeKey(sessionId), RuntimeState.class).orElseGet(RuntimeState::new);
        long nextSeq = state.getLastSeq() == null ? 1L : state.getLastSeq() + 1L;
        envelope.setSeq(nextSeq);
        state.setSessionId(sessionId);
        state.setLastSeq(nextSeq);
        if (envelope.getStreamId() != null) {
            state.setStreamId(envelope.getStreamId());
        }
        writeRuntimeState(sessionId, state);
        List<ServerEventEnvelope> events = readEventsAfter(sessionId, 0L);
        events = new ArrayList<>(events);
        events.add(envelope);
        writeJson(eventsKey(sessionId), events);
        return envelope;
    }

    public void acknowledge(Long sessionId, long ackSeq) {
        RuntimeState state = read(runtimeKey(sessionId), RuntimeState.class).orElseGet(RuntimeState::new);
        state.setSessionId(sessionId);
        state.setLastAckSeq(ackSeq);
        writeRuntimeState(sessionId, state);
    }

    public List<ServerEventEnvelope> readEventsAfter(Long sessionId, long seq) {
        List<ServerEventEnvelope> events = read(eventsKey(sessionId), new TypeReference<List<ServerEventEnvelope>>() {
        }).orElse(List.of());
        return events.stream()
                .filter(event -> event.getSeq() != null && event.getSeq() > seq)
                .toList();
    }

    public Optional<RuntimeSnapshot> readSnapshot(Long sessionId) {
        RuntimeState runtimeState = read(runtimeKey(sessionId), RuntimeState.class).orElse(null);
        ShortTermContext shortTermContext = read(shortTermKey(sessionId), ShortTermContext.class).orElse(null);
        StreamState streamState = read(streamKey(sessionId), StreamState.class).orElse(null);
        List<ServerEventEnvelope> events = read(eventsKey(sessionId), new TypeReference<List<ServerEventEnvelope>>() {
        }).orElse(List.of());
        if (runtimeState == null && shortTermContext == null && streamState == null && events.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RuntimeSnapshot(runtimeState, shortTermContext, streamState, events));
    }

    public void clearRuntime(Long sessionId) {
        stringRedisTemplate.delete(List.of(runtimeKey(sessionId), shortTermKey(sessionId), streamKey(sessionId), eventsKey(sessionId)));
    }

    @Transactional
    public void expireDrafts(Instant now) {
        LocalDateTime threshold = LocalDateTime.now().minus(TTL);
        List<ChatSession> sessions = chatSessionRepository.findAll().stream()
                .filter(session -> session.getSessionKind() == ChatSessionKind.DRAFT)
                .filter(session -> session.getDraftStatus() == ChatDraftStatus.DRAFT_ACTIVE)
                .filter(session -> session.getLastActiveAt() != null && session.getLastActiveAt().isBefore(threshold))
                .toList();
        for (ChatSession session : sessions) {
            session.setDraftStatus(ChatDraftStatus.DRAFT_EXPIRED);
            chatSessionRepository.save(session);
        }
    }

    private <T> void writeJson(String key, T value) {
        try {
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), TTL);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to write runtime state", ex);
        }
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        try {
            String value = stringRedisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, type));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read runtime state", ex);
        }
    }

    private <T> Optional<T> read(String key, TypeReference<T> type) {
        try {
            String value = stringRedisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, type));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read runtime state", ex);
        }
    }

    private String runtimeKey(Long sessionId) {
        return "chat:" + sessionId + ":runtime";
    }

    private String shortTermKey(Long sessionId) {
        return "chat:" + sessionId + ":short_term";
    }

    private String streamKey(Long sessionId) {
        return "chat:" + sessionId + ":stream";
    }

    private String eventsKey(Long sessionId) {
        return "chat:" + sessionId + ":events";
    }
}
