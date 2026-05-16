package com.noteweave.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.model.ChatDraftStatus;
import com.noteweave.chat.model.ChatRuntimeStatus;
import com.noteweave.chat.model.ChatScopeType;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.model.ChatSessionKind;
import com.noteweave.chat.model.ChatSessionStatus;
import com.noteweave.chat.model.ChatSessionType;
import com.noteweave.chat.repository.ChatSessionRepository;
import com.noteweave.chat.runtime.protocol.ServerEventEnvelope;
import com.noteweave.chat.runtime.service.ChatRuntimeStateStore;
import com.noteweave.chat.runtime.service.RuntimeSnapshot;
import com.noteweave.chat.runtime.service.RuntimeState;
import com.noteweave.chat.runtime.service.ShortTermContext;
import com.noteweave.chat.runtime.service.StreamState;
import com.noteweave.support.ContainerizedIntegrationTest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ChatRuntimeStateStoreTest extends ContainerizedIntegrationTest {

    @Autowired
    private ChatRuntimeStateStore chatRuntimeStateStore;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldPersistRuntimeStateAndReplayEventsAfterAck() {
        ChatSession session = createDraftSession();
        Long sessionId = session.getId();

        chatRuntimeStateStore.writeRuntimeState(sessionId, RuntimeState.builder()
                .sessionId(sessionId)
                .userId(session.getUserId())
                .spaceId(session.getSpaceId())
                .sessionKind(session.getSessionKind())
                .runtimeStatus(ChatRuntimeStatus.RUNNING)
                .streamId("stream-1")
                .requestId("request-1")
                .lastAckSeq(0L)
                .build());
        chatRuntimeStateStore.writeShortTermContext(sessionId, ShortTermContext.builder()
                .recentMessages(List.of("USER: hello"))
                .evidenceTitles(List.of("Deploy Guide"))
                .build());
        chatRuntimeStateStore.writeStreamState(sessionId, StreamState.builder()
                .streamId("stream-1")
                .status(ChatRuntimeStatus.RUNNING)
                .partialContent("partial answer")
                .build());

        ServerEventEnvelope started = chatRuntimeStateStore.appendEvent(sessionId, ServerEventEnvelope.builder()
                .event("chat.started")
                .streamId("stream-1")
                .sessionId(sessionId)
                .payload(objectMapper.valueToTree(Map.of("status", "RUNNING")))
                .build());
        ServerEventEnvelope delta = chatRuntimeStateStore.appendEvent(sessionId, ServerEventEnvelope.builder()
                .event("chat.delta")
                .streamId("stream-1")
                .sessionId(sessionId)
                .payload(objectMapper.valueToTree(Map.of("delta", "partial")))
                .build());

        chatRuntimeStateStore.acknowledge(sessionId, started.getSeq());

        RuntimeSnapshot snapshot = chatRuntimeStateStore.readSnapshot(sessionId).orElseThrow();
        assertThat(snapshot.runtimeState().getLastAckSeq()).isEqualTo(started.getSeq());
        assertThat(snapshot.streamState().getPartialContent()).isEqualTo("partial answer");
        assertThat(snapshot.shortTermContext().getEvidenceTitles()).containsExactly("Deploy Guide");
        assertThat(chatRuntimeStateStore.readEventsAfter(sessionId, started.getSeq()))
                .extracting(ServerEventEnvelope::getEvent)
                .containsExactly("chat.delta");
        assertThat(delta.getSeq()).isGreaterThan(started.getSeq());
    }

    @Test
    void shouldExpireDraftSessionsAfterTtl() {
        ChatSession session = createDraftSession();
        session.setLastActiveAt(LocalDateTime.ofInstant(Instant.now().minusSeconds(3 * 60 * 60), ZoneOffset.UTC));
        chatSessionRepository.save(session);

        chatRuntimeStateStore.expireDrafts(Instant.now());

        ChatSession reloaded = chatSessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getDraftStatus()).isEqualTo(ChatDraftStatus.DRAFT_EXPIRED);
    }

    private ChatSession createDraftSession() {
        ChatSession session = new ChatSession();
        session.setUserId(1L);
        session.setSpaceId(1L);
        session.setSessionType(ChatSessionType.TEAM_CHAT);
        session.setSessionKind(ChatSessionKind.DRAFT);
        session.setScopeType(ChatScopeType.SPACE);
        session.setTitle("draft session");
        session.setStatus(ChatSessionStatus.ACTIVE);
        session.setRuntimeStatus(ChatRuntimeStatus.IDLE);
        session.setDraftStatus(ChatDraftStatus.DRAFT_ACTIVE);
        session.setLastActiveAt(LocalDateTime.now());
        return chatSessionRepository.save(session);
    }
}
