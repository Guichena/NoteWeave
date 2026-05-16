package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.runtime.service.ChatRuntimeStateStore;
import com.noteweave.chat.runtime.service.RuntimeSnapshot;
import com.noteweave.support.ContainerizedIntegrationTest;
import com.noteweave.team.document.dto.DocumentProcessTaskPayload;
import com.noteweave.team.document.service.DocumentProcessingService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "noteweave.chat.runtime.delta-chunk-size=12",
        "noteweave.chat.runtime.delta-delay-ms=80"
})
class Phase5WorkspaceChatRuntimeIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentProcessingService documentProcessingService;

    @Autowired
    private ChatRuntimeStateStore chatRuntimeStateStore;

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private WebSocket openSocket;

    @AfterEach
    void tearDown() {
        if (openSocket != null) {
            openSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test");
            openSocket = null;
        }
    }

    @Test
    void formalSessionShouldStreamAndPersistAssistantMessageWithCitations() throws Exception {
        String ownerToken = registerAndGetToken("phase5_formal_owner_" + System.nanoTime());
        Long spaceId = createTeamSpace(ownerToken, "phase5-formal-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase5-formal-kb-" + System.nanoTime());
        uploadAndProcess(ownerToken, spaceId, kbId, "formal.txt", "text/plain",
                longEvidenceText("Rollback should be rehearsed before every production release."));
        Long sessionId = createChatSession(ownerToken, spaceId, "formal ws", "FORMAL", "KNOWLEDGE_BASE", new long[]{kbId});

        TestListener listener = new TestListener(objectMapper);
        openSocket = connect(listener, ownerToken);
        awaitEvent(listener.events(), "chat.connected");

        openSocket.sendText(chatMessageEvent(sessionId, spaceId, "FORMAL", "KNOWLEDGE_BASE", new long[]{kbId},
                "What is the rollback requirement?"), true).join();

        WsEvent started = awaitEvent(listener.events(), "chat.started");
        WsEvent delta = awaitLastEventBefore(listener.events(), "chat.delta", "chat.completed");
        WsEvent completed = awaitEvent(listener.events(), "chat.completed");

        assertThat(delta.seq()).isGreaterThan(started.seq());
        assertThat(completed.seq()).isGreaterThan(delta.seq());
        assertThat(completed.payload().path("assistantMessageId").asLong()).isPositive();
        assertThat(completed.payload().path("citations")).isNotEmpty();

        assertThat(jdbcTemplate.queryForObject("select count(*) from chat_message where session_id = ?", Integer.class, sessionId))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "select status from chat_message where id = ?",
                        String.class,
                        completed.payload().path("assistantMessageId").asLong()))
                .isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from message_citation where message_id = ?", Integer.class,
                        completed.payload().path("assistantMessageId").asLong()))
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select runtime_status from chat_session where id = ?", String.class, sessionId))
                .isEqualTo("IDLE");
    }

    @Test
    void stopShouldPreventFurtherDeltaAndKeepOnlyUserMessagePersisted() throws Exception {
        String ownerToken = registerAndGetToken("phase5_stop_owner_" + System.nanoTime());
        Long spaceId = createTeamSpace(ownerToken, "phase5-stop-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase5-stop-kb-" + System.nanoTime());
        uploadAndProcess(ownerToken, spaceId, kbId, "stop.txt", "text/plain",
                longEvidenceText("Rollback rehearsal is mandatory before deployment and must be documented."));
        Long sessionId = createChatSession(ownerToken, spaceId, "stop ws", "FORMAL", "KNOWLEDGE_BASE", new long[]{kbId});

        TestListener listener = new TestListener(objectMapper);
        openSocket = connect(listener, ownerToken);
        awaitEvent(listener.events(), "chat.connected");

        openSocket.sendText(chatMessageEvent(sessionId, spaceId, "FORMAL", "KNOWLEDGE_BASE", new long[]{kbId},
                "Explain the rollback requirement in detail."), true).join();

        awaitEvent(listener.events(), "chat.started");
        WsEvent firstDelta = awaitEvent(listener.events(), "chat.delta");

        openSocket.sendText("""
                {"event":"chat.stop","requestId":"stop-request","streamId":"%s","sessionId":%d,"ack":%d,"payload":{}}
                """.formatted(firstDelta.streamId(), sessionId, firstDelta.seq()), true).join();

        WsEvent stopped = awaitEvent(listener.events(), "chat.stopped");
        assertThat(stopped.seq()).isGreaterThan(firstDelta.seq());
        assertNoMoreTerminalOrDeltaEvents(listener.events(), Duration.ofMillis(500));

        assertThat(jdbcTemplate.queryForObject("select count(*) from chat_message where session_id = ?", Integer.class, sessionId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select runtime_status from chat_session where id = ?", String.class, sessionId))
                .isEqualTo("STOPPED");
        RuntimeSnapshot snapshot = chatRuntimeStateStore.readSnapshot(sessionId).orElseThrow();
        assertThat(snapshot.streamState().getPartialContent()).isNotBlank();
    }

    @Test
    void stopShouldRestoreStoppedStatusAfterReconnect() throws Exception {
        String ownerToken = registerAndGetToken("phase5_stop_restore_owner_" + System.nanoTime());
        Long spaceId = createTeamSpace(ownerToken, "phase5-stop-restore-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase5-stop-restore-kb-" + System.nanoTime());
        uploadAndProcess(ownerToken, spaceId, kbId, "stop-restore.txt", "text/plain",
                longEvidenceText("Rollback rehearsal remains mandatory before production deployment."));
        Long sessionId = createChatSession(ownerToken, spaceId, "stop restore ws", "FORMAL", "KNOWLEDGE_BASE", new long[]{kbId});

        TestListener firstListener = new TestListener(objectMapper);
        openSocket = connect(firstListener, ownerToken);
        awaitEvent(firstListener.events(), "chat.connected");
        openSocket.sendText(chatMessageEvent(sessionId, spaceId, "FORMAL", "KNOWLEDGE_BASE", new long[]{kbId},
                "Explain the rollback requirement."), true).join();

        awaitEvent(firstListener.events(), "chat.started");
        WsEvent firstDelta = awaitEvent(firstListener.events(), "chat.delta");
        openSocket.sendText("""
                {"event":"chat.stop","requestId":"stop-restore-request","streamId":"%s","sessionId":%d,"ack":%d,"payload":{}}
                """.formatted(firstDelta.streamId(), sessionId, firstDelta.seq()), true).join();
        WsEvent stopped = awaitEvent(firstListener.events(), "chat.stopped");

        openSocket.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect").join();
        openSocket = null;

        TestListener secondListener = new TestListener(objectMapper);
        openSocket = connect(secondListener, ownerToken);
        awaitEvent(secondListener.events(), "chat.connected");
        openSocket.sendText("""
                {"event":"chat.resume","requestId":"stop-restore-resume","sessionId":%d,"ack":%d,"payload":{}}
                """.formatted(sessionId, stopped.seq()), true).join();

        WsEvent restored = awaitEvent(secondListener.events(), "chat.restored");
        assertThat(restored.payload().path("runtimeStatus").asText()).isEqualTo("STOPPED");
        assertThat(restored.payload().path("partialContent").asText()).isNotBlank();
    }

    @Test
    void resumeShouldReplayBufferedEventsAfterReconnect() throws Exception {
        String ownerToken = registerAndGetToken("phase5_resume_owner_" + System.nanoTime());
        Long spaceId = createTeamSpace(ownerToken, "phase5-resume-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase5-resume-kb-" + System.nanoTime());
        uploadAndProcess(ownerToken, spaceId, kbId, "resume.txt", "text/plain",
                longEvidenceText("Rollback rehearsal remains mandatory for every production deployment."));
        Long sessionId = createChatSession(ownerToken, spaceId, "resume ws", "FORMAL", "KNOWLEDGE_BASE", new long[]{kbId});

        TestListener firstListener = new TestListener(objectMapper);
        openSocket = connect(firstListener, ownerToken);
        awaitEvent(firstListener.events(), "chat.connected");

        openSocket.sendText(chatMessageEvent(sessionId, spaceId, "FORMAL", "KNOWLEDGE_BASE", new long[]{kbId},
                "Summarize the rollback requirement."), true).join();

        WsEvent started = awaitEvent(firstListener.events(), "chat.started");
        WsEvent firstDelta = awaitEvent(firstListener.events(), "chat.delta");
        openSocket.sendClose(WebSocket.NORMAL_CLOSURE, "refresh").join();
        openSocket = null;

        Thread.sleep(250L);

        TestListener secondListener = new TestListener(objectMapper);
        openSocket = connect(secondListener, ownerToken);
        awaitEvent(secondListener.events(), "chat.connected");
        openSocket.sendText("""
                {"event":"chat.resume","requestId":"resume-request","sessionId":%d,"ack":%d,"payload":{}}
                """.formatted(sessionId, firstDelta.seq()), true).join();

        WsEvent replayed = awaitAnyEvent(secondListener.events(), "chat.delta", "chat.completed");
        WsEvent restored = awaitEvent(secondListener.events(), "chat.restored");

        assertThat(replayed.seq()).isGreaterThan(firstDelta.seq());
        assertThat(restored.payload().path("runtimeStatus").asText()).isNotBlank();
        assertThat(restored.payload().path("partialContent").asText()).isNotBlank();
        assertThat(started.seq()).isLessThan(firstDelta.seq());
    }

    @Test
    void draftLifecycleShouldSupportConvertDiscardAndExpireRules() throws Exception {
        String ownerToken = registerAndGetToken("phase5_draft_owner_" + System.nanoTime());
        Long spaceId = createTeamSpace(ownerToken, "phase5-draft-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase5-draft-kb-" + System.nanoTime());

        Long convertSessionId = createChatSession(ownerToken, spaceId, "draft convert", "DRAFT", "KNOWLEDGE_BASE", new long[]{kbId});
        mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/convert-to-formal", convertSessionId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionKind").value("FORMAL"))
                .andExpect(jsonPath("$.data.draftStatus").value("CONVERTED"));

        Long discardSessionId = createChatSession(ownerToken, spaceId, "draft discard", "DRAFT", "KNOWLEDGE_BASE", new long[]{kbId});
        mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/discard-draft", discardSessionId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draftStatus").value("DISCARDED"));

        Long expiredSessionId = createChatSession(ownerToken, spaceId, "draft expire", "DRAFT", "KNOWLEDGE_BASE", new long[]{kbId});
        jdbcTemplate.update("update chat_session set last_active_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(3))), expiredSessionId);
        chatRuntimeStateStore.expireDrafts(Instant.now());

        mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/convert-to-formal", expiredSessionId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHAT_DRAFT_INVALID_STATE"));
    }

    @Test
    void draftSessionShouldKeepAssistantOutputEphemeralAndRejectExpiredWrites() throws Exception {
        String ownerToken = registerAndGetToken("phase5_draft_runtime_owner_" + System.nanoTime());
        Long spaceId = createTeamSpace(ownerToken, "phase5-draft-runtime-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase5-draft-runtime-kb-" + System.nanoTime());
        uploadAndProcess(ownerToken, spaceId, kbId, "draft-runtime.txt", "text/plain",
                longEvidenceText("Rollback rehearsal stays mandatory before production deployment."));
        Long sessionId = createChatSession(ownerToken, spaceId, "draft runtime ws", "DRAFT", "KNOWLEDGE_BASE", new long[]{kbId});

        TestListener listener = new TestListener(objectMapper);
        openSocket = connect(listener, ownerToken);
        awaitEvent(listener.events(), "chat.connected");

        openSocket.sendText(chatMessageEvent(sessionId, spaceId, "DRAFT", "KNOWLEDGE_BASE", new long[]{kbId},
                "What is the rollback requirement?"), true).join();

        WsEvent completed = awaitEvent(listener.events(), "chat.completed");
        assertThat(completed.payload().path("persisted").asBoolean()).isFalse();
        assertThat(completed.payload().path("assistantMessageId").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject("select count(*) from chat_message where session_id = ?", Integer.class, sessionId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from chat_message where session_id = ? and role = 'ASSISTANT'",
                Integer.class,
                sessionId)).isEqualTo(0);

        jdbcTemplate.update("update chat_session set last_active_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(3))), sessionId);
        chatRuntimeStateStore.expireDrafts(Instant.now());

        openSocket.sendText(chatMessageEvent(sessionId, spaceId, "DRAFT", "KNOWLEDGE_BASE", new long[]{kbId},
                "Can I keep asking this expired draft?"), true).join();

        WsEvent failed = awaitEvent(listener.events(), "chat.failed");
        assertThat(failed.error().path("code").asText()).isEqualTo("CHAT_DRAFT_INVALID_STATE");
        assertThat(jdbcTemplate.queryForObject("select count(*) from chat_message where session_id = ?", Integer.class, sessionId))
                .isEqualTo(1);
    }

    @Test
    void httpAskShouldRejectDraftSessions() throws Exception {
        String ownerToken = registerAndGetToken("phase5_http_draft_owner_" + System.nanoTime());
        Long spaceId = createTeamSpace(ownerToken, "phase5-http-draft-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase5-http-draft-kb-" + System.nanoTime());
        Long sessionId = createChatSession(ownerToken, spaceId, "draft http", "DRAFT", "KNOWLEDGE_BASE", new long[]{kbId});

        mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Should draft HTTP ask be allowed?"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHAT_DRAFT_INVALID_STATE"));
    }

    private WebSocket connect(TestListener listener, String token) throws Exception {
        String ticket = createWsTicket(token);
        return httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/chat/" + ticket), listener)
                .join();
    }

    private String createWsTicket(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/chat/ws-ticket")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("ticket").asText();
    }

    private WsEvent awaitEvent(BlockingQueue<WsEvent> queue, String eventName) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            WsEvent event = queue.poll(200, TimeUnit.MILLISECONDS);
            if (event == null) {
                continue;
            }
            if (Objects.equals(event.event(), eventName)) {
                return event;
            }
        }
        throw new AssertionError("Timed out waiting for event " + eventName);
    }

    private WsEvent awaitAnyEvent(BlockingQueue<WsEvent> queue, String... eventNames) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            WsEvent event = queue.poll(200, TimeUnit.MILLISECONDS);
            if (event == null) {
                continue;
            }
            if (Arrays.asList(eventNames).contains(event.event())) {
                return event;
            }
        }
        throw new AssertionError("Timed out waiting for any of " + Arrays.toString(eventNames));
    }

    private WsEvent awaitLastEventBefore(BlockingQueue<WsEvent> queue, String targetEvent, String terminalEvent) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        WsEvent lastTarget = null;
        while (System.nanoTime() < deadline) {
            WsEvent event = queue.poll(200, TimeUnit.MILLISECONDS);
            if (event == null) {
                continue;
            }
            if (targetEvent.equals(event.event())) {
                lastTarget = event;
                continue;
            }
            if (terminalEvent.equals(event.event())) {
                if (lastTarget == null) {
                    throw new AssertionError("Terminal event arrived before target event " + targetEvent);
                }
                queue.offer(event);
                return lastTarget;
            }
        }
        throw new AssertionError("Timed out waiting for " + targetEvent + " before " + terminalEvent);
    }

    private void assertNoMoreTerminalOrDeltaEvents(BlockingQueue<WsEvent> queue, Duration duration) throws Exception {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            WsEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
            if (event == null) {
                continue;
            }
            if ("chat.delta".equals(event.event()) || "chat.completed".equals(event.event())) {
                throw new AssertionError("Unexpected event after stop: " + event.event());
            }
        }
    }

    private String chatMessageEvent(Long sessionId, Long spaceId, String sessionKind, String scopeType, long[] scopeIds, String content) {
        return """
                {"event":"chat.message","requestId":"request-%d","streamId":"stream-%d","sessionId":%d,"payload":{"spaceId":%d,"sessionKind":"%s","scopeType":"%s","scopeIds":%s,"content":"%s"}}
                """.formatted(System.nanoTime(), System.nanoTime(), sessionId, spaceId, sessionKind, scopeType,
                Arrays.toString(scopeIds), content.replace("\"", "\\\""));
    }

    private byte[] longEvidenceText(String sentence) {
        String content = sentence + " " + sentence + " " + sentence + " " + sentence + " " + sentence;
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private IndexedDocument uploadAndProcess(
            String token,
            Long spaceId,
            Long kbId,
            String fileName,
            String contentType,
            byte[] content
    ) throws Exception {
        JsonNode merged = uploadAndMerge(token, kbId, fileName, contentType, content);
        Long documentId = merged.path("data").path("documentId").asLong();
        Long taskId = merged.path("data").path("taskId").asLong();
        documentProcessingService.process(payload(taskId, documentId, spaceId, kbId, fileName, contentType));
        return new IndexedDocument(documentId, taskId);
    }

    private Long createChatSession(String token, Long spaceId, String title, String sessionKind, String scopeType, long[] scopeIds) throws Exception {
        String scopeIdsJson = Arrays.toString(scopeIds);
        MvcResult result = mockMvc.perform(post("/api/v1/chat/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"spaceId":%d,"sessionType":"TEAM_CHAT","sessionKind":"%s","title":"%s","scopeType":"%s","scopeIds":%s}
                                """.formatted(spaceId, sessionKind, title, scopeType, scopeIdsJson)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private DocumentProcessTaskPayload payload(Long taskId, Long documentId, Long spaceId, Long kbId, String fileName, String contentType) {
        return DocumentProcessTaskPayload.builder()
                .taskId(taskId)
                .documentId(documentId)
                .spaceId(spaceId)
                .knowledgeBaseId(kbId)
                .fileName(fileName)
                .contentType(contentType)
                .build();
    }

    private JsonNode uploadAndMerge(String token, Long kbId, String fileName, String contentType, byte[] content) throws Exception {
        String fileMd5 = md5Hex(content);
        Map<String, Object> init = new HashMap<>();
        init.put("fileMd5", fileMd5);
        init.put("fileName", fileName);
        init.put("contentType", contentType);
        init.put("totalSize", content.length);
        init.put("chunkSize", content.length);
        init.put("totalChunks", 1);
        MvcResult initResult = mockMvc.perform(post("/api/v1/team/knowledge-bases/{knowledgeBaseId}/documents/uploads/init", kbId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(init)))
                .andExpect(status().isOk())
                .andReturn();
        Long uploadId = objectMapper.readTree(initResult.getResponse().getContentAsString()).path("data").path("uploadId").asLong();

        MockMultipartFile chunk = new MockMultipartFile("file", "chunk-0.bin", contentType, content);
        mockMvc.perform(multipart("/api/v1/team/document-uploads/{uploadId}/chunks", uploadId)
                        .file(chunk)
                        .param("chunkIndex", "0")
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        })
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        MvcResult mergeResult = mockMvc.perform(post("/api/v1/team/document-uploads/{uploadId}/merge", uploadId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(mergeResult.getResponse().getContentAsString());
    }

    private Long createKnowledgeBase(String token, Long spaceId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/knowledge-bases", spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"phase5 kb"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private Long createTeamSpace(String token, String spaceName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/spaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"phase5 team"}
                                """.formatted(spaceName)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private String registerAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s@example.com","password":"Password123!","displayName":"Phase5 User"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private String md5Hex(byte[] content) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] digest = md5.digest(content);
        StringBuilder builder = new StringBuilder();
        for (byte b : digest) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private record IndexedDocument(Long documentId, Long taskId) {
    }

    private record WsEvent(String event, String streamId, long seq, JsonNode payload, JsonNode error) {
    }

    private static final class TestListener implements WebSocket.Listener {

        private final ObjectMapper objectMapper;
        private final BlockingQueue<WsEvent> events = new LinkedBlockingQueue<>();
        private final StringBuilder currentMessage = new StringBuilder();

        private TestListener(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        public BlockingQueue<WsEvent> events() {
            return events;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            currentMessage.append(data);
            if (last) {
                try {
                    JsonNode json = objectMapper.readTree(currentMessage.toString());
                    events.offer(new WsEvent(
                            json.path("event").asText(),
                            json.path("streamId").isMissingNode() ? null : json.path("streamId").asText(null),
                            json.path("seq").asLong(),
                            json.path("payload"),
                            json.path("error")));
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                } finally {
                    currentMessage.setLength(0);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
