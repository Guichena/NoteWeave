package com.noteweave.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.service.LlmClient;
import com.noteweave.support.ContainerizedIntegrationTest;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.service.TaskDispatcher;
import com.noteweave.team.document.dto.DocumentProcessTaskPayload;
import com.noteweave.team.document.service.DocumentProcessingService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Phase14ObservabilityEvaluationIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentProcessingService documentProcessingService;

    @Autowired
    private TaskDispatcher taskDispatcher;

    @MockBean
    private LlmClient llmClient;

    @AfterEach
    void tearDown() {
        reset(llmClient);
    }

    @Test
    void adminShouldManagePromptVersionsAndInspectLogsTracesAndFeedback() throws Exception {
        String adminUsername = "phase14_admin_" + System.nanoTime();
        registerAndGetToken(adminUsername);
        jdbcTemplate.update("update users set system_role = 'ADMIN' where username = ?", adminUsername);
        String adminToken = loginAndGetToken(adminUsername, "Password123!");

        String ownerToken = registerAndGetToken("phase14_owner_" + System.nanoTime());
        Long spaceId = createTeamSpace(ownerToken, "phase14-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase14-kb-" + System.nanoTime());
        IndexedDocument indexedDocument = uploadAndProcess(
                ownerToken,
                spaceId,
                kbId,
                "rollback.txt",
                "text/plain",
                "Rollback should be rehearsed before production release. Blue-green is preferred for deployment."
                        .getBytes(StandardCharsets.UTF_8)
        );

        JsonNode beforeList = readJson(mockMvc.perform(get("/api/v1/admin/prompt-versions")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("scene", "TEAM_RAG_CHAT"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(beforeList.path("data").isArray()).isTrue();
        assertThat(beforeList.path("data").size()).isGreaterThan(0);

        JsonNode createdPrompt = readJson(mockMvc.perform(post("/api/v1/admin/prompt-versions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Phase14 Team Chat Prompt",
                                  "scene": "TEAM_RAG_CHAT",
                                  "version": 999,
                                  "content": "You are Phase 14 prompt {{noResultText}}",
                                  "status": "DRAFT"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn());
        long promptVersionId = createdPrompt.path("data").path("id").asLong();

        mockMvc.perform(post("/api/v1/admin/prompt-versions/{promptVersionId}/activate", promptVersionId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(promptVersionId))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        JsonNode afterList = readJson(mockMvc.perform(get("/api/v1/admin/prompt-versions")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("scene", "TEAM_RAG_CHAT"))
                .andExpect(status().isOk())
                .andReturn());
        long activeCount = java.util.stream.StreamSupport.stream(afterList.path("data").spliterator(), false)
                .filter(node -> "ACTIVE".equals(node.path("status").asText()))
                .count();
        assertThat(activeCount).isEqualTo(1L);

        given(llmClient.chat(anyList(), any())).willReturn(llmResponse(
                "Rollback should be rehearsed before production release. [SOURCE#1]"
        ));

        Long sessionId = createChatSession(ownerToken, spaceId, "phase14-observe", "KNOWLEDGE_BASE", new long[]{kbId});
        JsonNode askResponse = readJson(mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"What is the rollback requirement?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").isNotEmpty())
                .andReturn());
        long assistantMessageId = askResponse.path("data").path("assistantMessageId").asLong();

        mockMvc.perform(post("/api/v1/chat/messages/{messageId}/feedback", assistantMessageId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":"UP","reason":"HELPFUL","comment":"grounded answer"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rating").value("UP"))
                .andExpect(jsonPath("$.data.reason").value("HELPFUL"));

        mockMvc.perform(get("/api/v1/chat/messages/{messageId}/feedback", assistantMessageId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageId").value(assistantMessageId))
                .andExpect(jsonPath("$.data.rating").value("UP"))
                .andExpect(jsonPath("$.data.comment").value("grounded answer"));

        mockMvc.perform(post("/api/v1/chat/messages/{messageId}/feedback", assistantMessageId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":"MAYBE","reason":"HELPFUL","comment":"invalid"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        JsonNode llmLogs = readJson(mockMvc.perform(get("/api/v1/admin/llm-call-logs")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("sessionId", String.valueOf(sessionId))
                        .param("scene", "TEAM_RAG_CHAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].scene").value("TEAM_RAG_CHAT"))
                .andExpect(jsonPath("$.data.items[0].success").value(true))
                .andReturn());
        JsonNode firstLog = llmLogs.path("data").path("items").get(0);
        assertThat(firstLog.path("promptVersionId").asLong()).isEqualTo(promptVersionId);
        assertThat(firstLog.path("totalTokens").asInt()).isGreaterThan(0);
        assertThat(firstLog.path("promptHash").asText()).isNotBlank();

        Long traceId = jdbcTemplate.queryForObject(
                "select id from retrieval_trace where session_id = ? order by id desc limit 1",
                Long.class,
                sessionId
        );

        mockMvc.perform(get("/api/v1/admin/retrieval-traces/{traceId}", traceId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(traceId))
                .andExpect(jsonPath("$.data.scene").value("TEAM_RAG_CHAT"))
                .andExpect(jsonPath("$.data.queryText").value("What is the rollback requirement?"))
                .andExpect(jsonPath("$.data.items[0].documentId").value(indexedDocument.documentId()))
                .andExpect(jsonPath("$.data.items[0].selectedAsEvidence").value(true));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from message_citation where message_id = ? and retrieval_trace_id = ?",
                Integer.class,
                assistantMessageId,
                traceId
        )).isGreaterThanOrEqualTo(1);
    }

    @Test
    void spaceOwnerShouldManageEvalCasesAndRunMetricsWithoutPollutingChatHistory() throws Exception {
        String ownerToken = registerAndGetToken("phase14_eval_owner_" + System.nanoTime());
        Long spaceId = createTeamSpace(ownerToken, "phase14-eval-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase14-eval-kb-" + System.nanoTime());
        IndexedDocument indexedDocument = uploadAndProcess(
                ownerToken,
                spaceId,
                kbId,
                "incident.txt",
                "text/plain",
                "Escalation must happen within fifteen minutes. Incident commanders should capture the timeline."
                        .getBytes(StandardCharsets.UTF_8)
        );
        Map<String, Object> createCasePayload = new HashMap<>();
        createCasePayload.put("name", "Incident Escalation Case");
        createCasePayload.put("queryText", "What is the escalation deadline?");
        createCasePayload.put("expectedAnswer", "Escalation must happen within fifteen minutes.");
        createCasePayload.put("expectedSourceJson", "{\"documentId\":%d}".formatted(indexedDocument.documentId()));
        createCasePayload.put("tagsJson", "[\"incident\",\"phase14\"]");
        createCasePayload.put("enabled", true);

        JsonNode createdCase = readJson(mockMvc.perform(post("/api/v1/admin/spaces/{spaceId}/rag-eval-cases", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createCasePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andReturn());
        long caseId = createdCase.path("data").path("id").asLong();

        Map<String, Object> updateCasePayload = new HashMap<>();
        updateCasePayload.put("name", "Incident Escalation Case v2");
        updateCasePayload.put("queryText", "What is the escalation deadline?");
        updateCasePayload.put("expectedAnswer", "Escalation must happen within fifteen minutes.");
        updateCasePayload.put("expectedSourceJson", "{\"documentId\":%d}".formatted(indexedDocument.documentId()));
        updateCasePayload.put("tagsJson", "[\"incident\",\"phase14\",\"updated\"]");
        updateCasePayload.put("enabled", true);

        mockMvc.perform(put("/api/v1/admin/rag-eval-cases/{caseId}", caseId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateCasePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Incident Escalation Case v2"));

        mockMvc.perform(get("/api/v1/admin/spaces/{spaceId}/rag-eval-cases", spaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(caseId));

        given(llmClient.chat(anyList(), any())).willReturn(llmResponse(
                "Escalation must happen within fifteen minutes. [SOURCE#1]"
        ));

        JsonNode startRun = readJson(mockMvc.perform(post("/api/v1/admin/spaces/{spaceId}/rag-eval-runs", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Phase14 Eval Run"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn());
        long runId = startRun.path("data").path("id").asLong();
        long taskId = objectMapper.readTree(startRun.path("data").path("summaryJson").asText()).path("taskId").asLong();

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.SUCCESS);

        JsonNode runResponse = readJson(mockMvc.perform(get("/api/v1/admin/rag-eval-runs/{runId}", runId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andReturn());
        JsonNode summary = objectMapper.readTree(runResponse.path("data").path("summaryJson").asText());
        assertThat(summary.path("successCount").asInt()).isEqualTo(1);
        assertThat(summary.path("inputTokens").asInt()).isGreaterThan(0);

        JsonNode results = readJson(mockMvc.perform(get("/api/v1/admin/rag-eval-runs/{runId}/results", runId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode firstResult = results.path("data").get(0);
        assertThat(decimal(firstResult, "recallAtK")).isEqualByComparingTo(new BigDecimal("1.0000"));
        assertThat(decimal(firstResult, "mrr")).isGreaterThan(BigDecimal.ZERO);
        assertThat(decimal(firstResult, "citationCoverage")).isEqualByComparingTo(new BigDecimal("1.0000"));
        assertThat(firstResult.path("retrievalTraceId").asLong()).isGreaterThan(0L);
        assertThat(firstResult.path("llmCallLogId").asLong()).isGreaterThan(0L);
        assertThat(firstResult.path("latencyMs").asLong()).isGreaterThan(0L);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from chat_session where space_id = ?",
                Integer.class,
                spaceId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from chat_message where session_id in (select id from chat_session where space_id = ?)",
                Integer.class,
                spaceId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from retrieval_trace where task_id = ? and scene = 'RAG_EVAL'",
                Integer.class,
                taskId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from llm_call_log where task_id = ? and scene = 'RAG_EVAL' and success = b'1'",
                Integer.class,
                taskId
        )).isEqualTo(1);
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

    private Long createChatSession(String token, Long spaceId, String title, String scopeType, long[] scopeIds) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/chat/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"spaceId":%d,"sessionType":"TEAM_CHAT","title":"%s","scopeType":"%s","scopeIds":%s}
                                """.formatted(spaceId, title, scopeType, java.util.Arrays.toString(scopeIds))))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
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
        Long uploadId = readJson(initResult).path("data").path("uploadId").asLong();

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
        return readJson(mergeResult);
    }

    private Long createKnowledgeBase(String token, Long spaceId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/knowledge-bases", spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"phase14 kb"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private Long createTeamSpace(String token, String spaceName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/spaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"phase14 team"}
                                """.formatted(spaceName)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private String registerAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s@example.com","password":"Password123!","displayName":"Phase14 User"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("accessToken").asText();
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usernameOrEmail":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("accessToken").asText();
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
            sleepBriefly();
        }
        throw new AssertionError("Timed out waiting for task " + taskId + " to reach " + expectedStatus);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", ex);
        }
    }

    private BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(node.path(field).asText());
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

    private LlmResponse llmResponse(String content) {
        return LlmResponse.builder()
                .provider("test")
                .model("phase14-test")
                .content(content)
                .inputTokens(32)
                .outputTokens(48)
                .latencyMs(5L)
                .build();
    }

    private record IndexedDocument(Long documentId, Long taskId) {
    }
}
