package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.service.LlmClient;
import com.noteweave.personal.source.fetch.FetchedUrlContent;
import com.noteweave.personal.source.fetch.UrlContentFetcher;
import com.noteweave.support.ContainerizedIntegrationTest;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.service.TaskDispatcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Phase11_6ChatMcpIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskDispatcher taskDispatcher;

    @LocalServerPort
    private int port;

    @MockBean
    private LlmClient llmClient;

    @MockBean
    private UrlContentFetcher urlContentFetcher;

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
    void httpChatShouldTriggerBilibiliMcpArtifactTool() throws Exception {
        String ownerToken = registerAndGetToken("phase11_6_http_" + System.nanoTime());
        Long spaceId = createTeamSpace(ownerToken, "phase11-6-http-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase11-6-http-kb-" + System.nanoTime());
        Long sessionId = createChatSession(ownerToken, spaceId, "http mcp", "KNOWLEDGE_BASE", new long[]{kbId});

        String videoUrl = "https://www.bilibili.com/video/BV1abc123xyz/";
        stubBilibili(videoUrl);
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("""
                        # Bilibili Chat Notes

                        MCP-triggered artifact generation from chat.
                        """));

        MvcResult askResult = mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"/mcp bilibili %s topic=Bilibili_Chat_Notes"}
                                """.formatted(videoUrl)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolName").value("bilibili"))
                .andExpect(jsonPath("$.data.artifactId").isNumber())
                .andExpect(jsonPath("$.data.taskId").isNumber())
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("artifactId=")))
                .andReturn();

        JsonNode data = objectMapper.readTree(askResult.getResponse().getContentAsString()).path("data");
        Long artifactId = data.path("artifactId").asLong();
        Long taskId = data.path("taskId").asLong();

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.SUCCESS);

        assertThat(jdbcTemplate.queryForObject(
                "select status from artifact where id = ?",
                String.class,
                artifactId
        )).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject(
                "select artifact_id from chat_message where id = ?",
                Long.class,
                data.path("assistantMessageId").asLong()
        )).isEqualTo(artifactId);
    }

    @Test
    void httpChatShouldTriggerPersonalArtifactGenerationFromCommand() throws Exception {
        String ownerToken = registerAndGetToken("phase11_6_artifact_" + System.nanoTime());
        Long personalSpaceId = myPersonalSpaceId(ownerToken);
        Long projectId = createProject(ownerToken, "Command project", "chat artifact", "generate from chat");
        Long sourceId = addTextSource(ownerToken, projectId, "Command source", "RAG combines retrieval and generation.")
                .path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("""
                        {
                          "title": "Command source",
                          "summary": "Command source summary.",
                          "keyPoints": ["RAG combines retrieval and generation"],
                          "tags": ["RAG"],
                          "evidenceQuotes": [{"quote":"RAG combines retrieval and generation.","sourceId":%d,"reason":"source"}]
                        }
                        """.formatted(sourceId)))
                .willReturn(llmResponse("""
                        {
                          "concepts": [
                            {
                              "name": "RAG",
                              "aliases": ["Retrieval-Augmented Generation"],
                              "definition": "A retrieval plus generation pattern.",
                              "explanation": "RAG fetches context before generation.",
                              "useCases": ["Grounded assistants"],
                              "commonMisunderstandings": ["It removes retrieval quality work"],
                              "evidence": {"sourceId": %d, "quote": "RAG combines retrieval and generation."},
                              "confidence": 0.94
                            }
                          ],
                          "relations": []
                        }
                        """.formatted(sourceId)));
        Long compileTaskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(compileTaskId, TaskStatus.SUCCESS);

        Long sessionId = createChatSession(ownerToken, personalSpaceId, "artifact command", "SPACE", new long[]{personalSpaceId});
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("""
                        # Chat Command Report

                        Generated from personal research context.
                        """));

        MvcResult askResult = mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"/成果 Chat_Command_Report type=研究报告 project=%d"}
                                """.formatted(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolName").value("artifact"))
                .andExpect(jsonPath("$.data.artifactId").isNumber())
                .andExpect(jsonPath("$.data.taskId").isNumber())
                .andReturn();

        JsonNode data = objectMapper.readTree(askResult.getResponse().getContentAsString()).path("data");
        Long artifactId = data.path("artifactId").asLong();
        Long taskId = data.path("taskId").asLong();

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.SUCCESS);

        assertThat(jdbcTemplate.queryForObject(
                "select research_project_id from artifact where id = ?",
                Long.class,
                artifactId
        )).isEqualTo(projectId);
        assertThat(jdbcTemplate.queryForObject(
                "select artifact_id from chat_message where id = ?",
                Long.class,
                data.path("assistantMessageId").asLong()
        )).isEqualTo(artifactId);
    }

    @Test
    void websocketChatShouldTriggerBilibiliMcpArtifactTool() throws Exception {
        String ownerToken = registerAndGetToken("phase11_6_ws_" + System.nanoTime());
        Long spaceId = createTeamSpace(ownerToken, "phase11-6-ws-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase11-6-ws-kb-" + System.nanoTime());
        Long sessionId = createChatSession(ownerToken, spaceId, "ws mcp", "KNOWLEDGE_BASE", new long[]{kbId});

        String videoUrl = "https://www.bilibili.com/video/BV1abc123xyz/";
        stubBilibili(videoUrl);
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("""
                        # Bilibili WS Notes

                        MCP-triggered artifact generation from websocket chat.
                        """));

        TestListener listener = new TestListener(objectMapper);
        openSocket = connect(listener, ownerToken);
        awaitEvent(listener.events(), "chat.connected");

        openSocket.sendText("""
                {"event":"chat.message","requestId":"req-%d","streamId":"stream-%d","sessionId":%d,"payload":{"content":"/mcp bilibili %s topic=Bilibili_WS_Notes"}}
                """.formatted(System.nanoTime(), System.nanoTime(), sessionId, videoUrl), true).join();

        awaitEvent(listener.events(), "chat.started");
        WsEvent completed = awaitEvent(listener.events(), "chat.completed");
        Long artifactId = completed.payload().path("artifactId").asLong();
        Long taskId = completed.payload().path("taskId").asLong();

        assertThat(completed.payload().path("toolName").asText()).isEqualTo("bilibili");
        assertThat(artifactId).isPositive();
        assertThat(taskId).isPositive();

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.SUCCESS);

        assertThat(jdbcTemplate.queryForObject(
                "select status from artifact where id = ?",
                String.class,
                artifactId
        )).isEqualTo("READY");
    }

    private void stubBilibili(String videoUrl) {
        String subtitleApiUrl = "https://api.bilibili.com/x/player/v2?bvid=BV1abc123xyz&cid=987654321";
        String subtitleJsonUrl = "https://i0.hdslb.com/bfs/subtitle/test.json";
        given(urlContentFetcher.fetch(videoUrl)).willReturn(fetchedJson(bilibiliHtml()));
        given(urlContentFetcher.fetch(subtitleApiUrl)).willReturn(fetchedJson("""
                {
                  "code": 0,
                  "data": {
                    "subtitle": {
                      "subtitles": [
                        {
                          "lan": "zh-CN",
                          "lan_doc": "中文",
                          "subtitle_url": "%s"
                        }
                      ]
                    }
                  }
                }
                """.formatted(subtitleJsonUrl)));
        given(urlContentFetcher.fetch(subtitleJsonUrl)).willReturn(fetchedJson("""
                {
                  "body": [
                    { "from": 0.2, "content": "这一节介绍 Skill 和受控编排。" },
                    { "from": 8.4, "content": "重点是把产物生成做成固定 plan，而不是完全自主 agent。" }
                  ]
                }
                """));
    }

    private WebSocket connect(TestListener listener, String token) throws Exception {
        String ticket = createWsTicket(token);
        return httpClient.newWebSocketBuilder()
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

    private Long createChatSession(String token, Long spaceId, String title, String scopeType, long[] scopeIds) throws Exception {
        String scopeIdsJson = java.util.Arrays.toString(scopeIds);
        MvcResult result = mockMvc.perform(post("/api/v1/chat/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"spaceId":%d,"sessionType":"TEAM_CHAT","title":"%s","scopeType":"%s","scopeIds":%s}
                                """.formatted(spaceId, title, scopeType, scopeIdsJson)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private Long myPersonalSpaceId(String token) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/spaces")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        JsonNode items = data.has("items") ? data.path("items") : data;
        for (JsonNode item : items) {
            if ("PERSONAL".equals(item.path("type").asText())) {
                return item.path("id").asLong();
            }
        }
        throw new AssertionError("Personal space not found");
    }

    private Long createProject(String token, String title, String description, String goal) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/personal/research-projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","description":"%s","researchGoal":"%s"}
                                """.formatted(title, description, goal)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private JsonNode addTextSource(String token, Long projectId, String title, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/personal/research-projects/{projectId}/sources/text", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", title, "content", content))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode compileSource(String token, Long sourceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/personal/sources/{sourceId}/compile", sourceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Long createKnowledgeBase(String token, Long spaceId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/knowledge-bases", spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"phase11-6 kb"}
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
                                {"name":"%s","description":"phase11-6 team"}
                                """.formatted(spaceName)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private String registerAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s@example.com","password":"Password123!","displayName":"Phase11.6 User"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private void waitForTaskStatus(Long taskId, TaskStatus expectedStatus) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            String current = jdbcTemplate.queryForObject(
                    "select task_status from task where id = ?",
                    String.class,
                    taskId
            );
            if (expectedStatus.name().equals(current)) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting", ex);
            }
        }
        throw new AssertionError("Timed out waiting for task " + taskId + " to reach " + expectedStatus);
    }

    private LlmResponse llmResponse(String content) {
        return LlmResponse.builder()
                .provider("test")
                .model("phase11-6-test")
                .content(content)
                .inputTokens(24)
                .outputTokens(48)
                .latencyMs(1L)
                .build();
    }

    private FetchedUrlContent fetchedJson(String body) {
        return new FetchedUrlContent(body.getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
    }

    private String bilibiliHtml() {
        return """
                <html>
                <head><title>Skill-based Studio 与知识沉淀</title></head>
                <body>
                <script>
                window.__INITIAL_STATE__={
                  "videoData":{
                    "title":"Skill-based Studio 与知识沉淀",
                    "desc":"介绍如何把报告、FAQ 和学习指南抽象为固定 Skill。",
                    "bvid":"BV1abc123xyz",
                    "cid":987654321,
                    "pubdate":1716800000,
                    "owner":{"name":"NoteWeave Lab"},
                    "pages":[
                      {"cid":987654321,"part":"Part 1. Studio 总览"},
                      {"cid":987654322,"part":"Part 2. 知识沉淀闭环"}
                    ]
                  }
                };
                </script>
                </body>
                </html>
                """;
    }

    private WsEvent awaitEvent(BlockingQueue<WsEvent> queue, String eventName) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            WsEvent event = queue.poll(200, TimeUnit.MILLISECONDS);
            if (event != null && Objects.equals(event.event(), eventName)) {
                return event;
            }
        }
        throw new AssertionError("Timed out waiting for event " + eventName);
    }

    private record WsEvent(String event, JsonNode payload) {
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
                    events.offer(new WsEvent(json.path("event").asText(), json.path("payload")));
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
