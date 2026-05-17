package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.support.ContainerizedIntegrationTest;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.runtime.service.ContextReadPlan;
import com.noteweave.chat.runtime.service.ContextReadRouter;
import com.noteweave.chat.service.ChatSessionService;
import com.noteweave.memory.service.MemoryContextService;
import com.noteweave.memory.service.PromptMemoryContext;
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
        "noteweave.chat.runtime.delta-delay-ms=20"
})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class Phase12LongTermMemoryIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentProcessingService documentProcessingService;

    @Autowired
    private MemoryContextService memoryContextService;

    @Autowired
    private ContextReadRouter contextReadRouter;

    @Autowired
    private ChatSessionService chatSessionService;

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
    void formalSessionShouldPersistSummariesAndExposeMemoryApis() throws Exception {
        String ownerToken = registerAndGetToken("phase12_formal_owner_" + System.nanoTime());
        Long spaceId = createTeamSpace(ownerToken, "phase12-formal-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase12-formal-kb-" + System.nanoTime());
        uploadAndProcess(
                ownerToken,
                spaceId,
                kbId,
                "memory.txt",
                "text/plain",
                "Release notes should stay concise and rollback must be rehearsed before every deployment."
                        .getBytes(StandardCharsets.UTF_8)
        );
        Long sessionId = createChatSession(ownerToken, spaceId, "formal memory", "FORMAL", new long[]{kbId});

        mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Please remember that I prefer concise bullet answers for this workspace. What is the rollback requirement?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").isNotEmpty())
                .andExpect(jsonPath("$.data.citations[0].sourceType").value("DOCUMENT"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from session_summary where session_id = ?", Integer.class, sessionId))
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from memory_item where user_id = ? and space_id = ?", Integer.class,
                        userIdByToken(ownerToken), spaceId))
                .isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/api/v1/chat/sessions/{sessionId}/summaries", sessionId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sessionId").value(sessionId))
                .andExpect(jsonPath("$.data[0].summary").isNotEmpty());

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/memory", spaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.spaceId").value(spaceId))
                .andExpect(jsonPath("$.data.items[0].topic").isNotEmpty());

        mockMvc.perform(get("/api/v1/users/me/memory")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.writeEnabled").value(true))
                .andExpect(jsonPath("$.data.items[0].memoryType").isNotEmpty());
    }

    @Test
    void draftAndDisabledWritebackShouldNotPersistLongTermMemory() throws Exception {
        String ownerToken = registerAndGetToken("phase12_draft_owner_" + System.nanoTime());
        Long spaceId = createTeamSpace(ownerToken, "phase12-draft-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase12-draft-kb-" + System.nanoTime());
        uploadAndProcess(
                ownerToken,
                spaceId,
                kbId,
                "draft.txt",
                "text/plain",
                "Rollback rehearsal stays mandatory before every production deployment."
                        .getBytes(StandardCharsets.UTF_8)
        );
        Long draftSessionId = createChatSession(ownerToken, spaceId, "draft memory", "DRAFT", new long[]{kbId});

        TestListener listener = new TestListener(objectMapper);
        openSocket = connect(listener, ownerToken);
        awaitEvent(listener.events(), "chat.connected");
        openSocket.sendText(chatMessageEvent(draftSessionId, spaceId, "DRAFT", new long[]{kbId},
                "Please remember this draft preference and answer my question."), true).join();
        awaitEvent(listener.events(), "chat.completed");

        assertThat(jdbcTemplate.queryForObject("select count(*) from session_summary where session_id = ?", Integer.class, draftSessionId))
                .isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject("select count(*) from memory_item where space_id = ?", Integer.class, spaceId))
                .isEqualTo(0);

        mockMvc.perform(post("/api/v1/users/me/memory/disable")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.writeEnabled").value(false));

        Long formalSessionId = createChatSession(ownerToken, spaceId, "formal disabled", "FORMAL", new long[]{kbId});
        mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", formalSessionId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Please remember that I prefer concise answers. What is the rollback requirement?"}
                                """))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("select count(*) from session_summary where session_id = ?", Integer.class, formalSessionId))
                .isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject("select count(*) from memory_item where space_id = ?", Integer.class, spaceId))
                .isEqualTo(0);
    }

    @Test
    void memoryApisShouldRespectPrivacyAndPinShouldSurviveExpiryUntilDeleted() throws Exception {
        String ownerName = "phase12_owner_" + System.nanoTime();
        String viewerName = "phase12_viewer_" + System.nanoTime();
        String ownerToken = registerAndGetToken(ownerName);
        String viewerToken = registerAndGetToken(viewerName);

        Long spaceId = createTeamSpace(ownerToken, "phase12-privacy-space-" + System.nanoTime());
        addMember(ownerToken, spaceId, viewerName + "@example.com", "VIEWER");

        MvcResult itemResult = mockMvc.perform(put("/api/v1/spaces/{spaceId}/memory", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memoryType":"SPACE_CONTEXT","topic":"workspace-style","summary":"Use concise bullet answers in this workspace.","importanceScore":0.9,"confidenceScore":1.0,"pin":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.id").isNumber())
                .andReturn();
        Long memoryItemId = objectMapper.readTree(itemResult.getResponse().getContentAsString()).path("data").path("item").path("id").asLong();

        jdbcTemplate.update("update memory_item set expires_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(2))), memoryItemId);

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/memory", spaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(memoryItemId))
                .andExpect(jsonPath("$.data.items[0].pin").value(true));

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/memory", spaceId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        mockMvc.perform(delete("/api/v1/spaces/{spaceId}/memory/{memoryItemId}", spaceId, memoryItemId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/memory", spaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void expiredPinnedMemoriesShouldRemainVisibleButStayOutOfDefaultPromptContext() throws Exception {
        String ownerName = "phase12_prompt_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(ownerName);
        Long ownerUserId = userIdByUsername(ownerName);
        Long spaceId = createTeamSpace(ownerToken, "phase12-prompt-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase12-prompt-kb-" + System.nanoTime());
        Long sessionId = createChatSession(ownerToken, spaceId, "prompt context", "FORMAL", new long[]{kbId});

        MvcResult spaceItemResult = mockMvc.perform(put("/api/v1/spaces/{spaceId}/memory", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memoryType":"SPACE_CONTEXT","topic":"workspace-style","summary":"Pinned but expired workspace memory.","importanceScore":0.9,"confidenceScore":0.95,"pin":true}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        Long spaceMemoryItemId = objectMapper.readTree(spaceItemResult.getResponse().getContentAsString())
                .path("data").path("item").path("id").asLong();

        MvcResult userItemResult = mockMvc.perform(put("/api/v1/users/me/memory")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memoryType":"USER_PREFERENCE","topic":"answer_style","summary":"Pinned but expired user preference.","importanceScore":0.8,"confidenceScore":0.9,"pin":true}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        Long userMemoryItemId = objectMapper.readTree(userItemResult.getResponse().getContentAsString())
                .path("data").path("item").path("id").asLong();

        jdbcTemplate.update("update memory_item set expires_at = ? where id in (?, ?)",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(1))), spaceMemoryItemId, userMemoryItemId);
        jdbcTemplate.update("""
                insert into session_summary (
                    user_id, space_id, session_id, topic, query_type, scope_type, summary,
                    importance_score, confidence_score, stale, pin, expires_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
                """,
                ownerUserId,
                spaceId,
                sessionId,
                "expired-summary",
                "TEAM_CHAT",
                "SPACE",
                "Pinned but expired session summary.",
                0.8,
                0.9,
                false,
                true,
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(1))));

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/memory", spaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].summary").value("Pinned but expired workspace memory."));

        ChatSession session = chatSessionService.getRequiredActiveSession(sessionId);
        ContextReadPlan plan = contextReadRouter.resolve(session.getSessionKind(), session.getSessionType());
        PromptMemoryContext promptMemoryContext = memoryContextService.load(ownerUserId, session, plan);

        assertThat(promptMemoryContext.spaceMemories()).doesNotContain("Pinned but expired workspace memory.");
        assertThat(promptMemoryContext.userMemories()).doesNotContain("Pinned but expired user preference.");
        assertThat(promptMemoryContext.sessionSummaries()).doesNotContain("Pinned but expired session summary.");
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

    private String chatMessageEvent(Long sessionId, Long spaceId, String sessionKind, long[] scopeIds, String content) {
        return """
                {"event":"chat.message","requestId":"request-%d","streamId":"stream-%d","sessionId":%d,"payload":{"spaceId":%d,"sessionKind":"%s","scopeType":"KNOWLEDGE_BASE","scopeIds":%s,"content":"%s"}}
                """.formatted(System.nanoTime(), System.nanoTime(), sessionId, spaceId, sessionKind,
                Arrays.toString(scopeIds), content.replace("\"", "\\\""));
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

    private Long createChatSession(String token, Long spaceId, String title, String sessionKind, long[] scopeIds) throws Exception {
        String scopeIdsJson = Arrays.toString(scopeIds);
        MvcResult result = mockMvc.perform(post("/api/v1/chat/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"spaceId":%d,"sessionType":"TEAM_CHAT","sessionKind":"%s","title":"%s","scopeType":"KNOWLEDGE_BASE","scopeIds":%s}
                                """.formatted(spaceId, sessionKind, title, scopeIdsJson)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private void addMember(String ownerToken, Long spaceId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/members", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"%s"}
                                """.formatted(email, role)))
                .andExpect(status().isOk());
    }

    private Long createKnowledgeBase(String token, Long spaceId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/knowledge-bases", spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"phase12 kb"}
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
                                {"name":"%s","description":"phase12 team"}
                                """.formatted(spaceName)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
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

    private String registerAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s@example.com","password":"Password123!","displayName":"Phase12 User"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private Long userIdByUsername(String username) {
        return jdbcTemplate.queryForObject(
                "select id from users where username = ?",
                Long.class,
                username
        );
    }

    private Long userIdByToken(String token) {
        try {
            String[] parts = token.split("\\.");
            String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return objectMapper.readTree(payloadJson).path("sub").asLong();
        } catch (Exception ex) {
            throw new AssertionError("Failed to parse user id from token", ex);
        }
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
