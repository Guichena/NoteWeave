package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.support.ContainerizedIntegrationTest;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.service.TaskDispatcher;
import com.noteweave.team.document.dto.DocumentProcessTaskPayload;
import com.noteweave.team.document.service.DocumentProcessingService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "noteweave.rag.retrieval.mode=HYBRID",
        "noteweave.embedding.enabled=true",
        "noteweave.embedding.stub.enabled=true"
})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class Phase9HybridRetrievalIntegrationTest extends ContainerizedIntegrationTest {

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

    @Test
    void shouldUseHybridRetrievalAndPersistExplainableTrace() throws Exception {
        String ownerToken = registerAndGetToken("phase9_owner_" + System.nanoTime());

        Long spaceId = createTeamSpace(ownerToken, "phase9-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase9-kb-" + System.nanoTime());
        IndexedDocument indexedDocument = uploadAndProcess(
                ownerToken,
                spaceId,
                kbId,
                "hybrid.txt",
                "text/plain",
                "Hybrid retrieval combines BM25 precision with vector semantics. Rollback drills remain mandatory before production deploys."
                        .getBytes(StandardCharsets.UTF_8)
        );

        Long sessionId = createChatSession(ownerToken, spaceId, "hybrid session", "KNOWLEDGE_BASE", new long[]{kbId});

        MvcResult askResult = mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"What is mandatory before production deploys?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").isNotEmpty())
                .andExpect(jsonPath("$.data.citations[0].sourceId").value(indexedDocument.documentId()))
                .andReturn();

        Long userMessageId = objectMapper.readTree(askResult.getResponse().getContentAsString())
                .path("data")
                .path("userMessageId")
                .asLong();

        Map<String, Object> trace = jdbcTemplate.queryForMap(
                "select retrieval_mode, bm25_count, vector_count, fusion_count, fallback_used, trace_json from retrieval_trace where message_id = ?",
                userMessageId
        );
        assertThat(trace.get("retrieval_mode")).isEqualTo("HYBRID");
        assertThat(((Number) trace.get("bm25_count")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) trace.get("fusion_count")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(trace.get("trace_json").toString())
                .contains("bm25")
                .contains("fusion");
    }

    @Test
    void searchDebugShouldExposeHybridCountsAndFallbackToBm25WhenVectorUnavailable() throws Exception {
        String ownerToken = registerAndGetToken("phase9_debug_owner_" + System.nanoTime());

        Long spaceId = createTeamSpace(ownerToken, "phase9-debug-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase9-debug-kb-" + System.nanoTime());
        uploadAndProcess(
                ownerToken,
                spaceId,
                kbId,
                "debug.txt",
                "text/plain",
                "Vector fallback must still return BM25 results with permission filters intact."
                        .getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(get("/api/v1/team/knowledge-bases/{knowledgeBaseId}/search", kbId)
                        .param("keyword", "permission filters")
                        .param("mode", "HYBRID")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrievalMode").value("HYBRID"))
                .andExpect(jsonPath("$.data.debug.bm25Count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.debug.fusionCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.items[0].documentTitle").value(org.hamcrest.Matchers.containsString("debug")));
    }

    @Test
    void shouldCreateEmbeddingBackfillTaskAndCompleteThroughWorkerChain() throws Exception {
        String ownerToken = registerAndGetToken("phase9_backfill_owner_" + System.nanoTime());

        Long spaceId = createTeamSpace(ownerToken, "phase9-backfill-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase9-backfill-kb-" + System.nanoTime());
        IndexedDocument indexedDocument = uploadAndProcess(
                ownerToken,
                spaceId,
                kbId,
                "backfill.txt",
                "text/plain",
                "Embedding backfill must run through task outbox and worker execution."
                        .getBytes(StandardCharsets.UTF_8)
        );

        JsonNode backfillTask = objectMapper.readTree(mockMvc.perform(post("/api/v1/team/documents/{documentId}/embedding-backfill", indexedDocument.documentId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskType").value("EMBEDDING_BACKFILL"))
                .andExpect(jsonPath("$.data.targetType").value("DOCUMENT"))
                .andExpect(jsonPath("$.data.targetId").value(indexedDocument.documentId()))
                .andReturn()
                .getResponse()
                .getContentAsString());

        Long taskId = backfillTask.path("data").path("id").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.SUCCESS);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select task_status, output_json from task where id = ?",
                taskId
        );
        assertThat(row.get("task_status")).isEqualTo(TaskStatus.SUCCESS.name());
        assertThat(String.valueOf(row.get("output_json")))
                .contains("\"documentId\":" + indexedDocument.documentId())
                .contains("\"backfilledChunkCount\":");
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
                                {"name":"%s","description":"phase9 kb"}
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
                                {"name":"%s","description":"phase9 team"}
                                """.formatted(spaceName)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private String registerAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s@example.com","password":"Password123!","displayName":"Phase9 User"}
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

    private void waitForTaskStatus(Long taskId, TaskStatus expectedStatus) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "select task_status, error_message from task where id = ?",
                    taskId
            );
            String current = String.valueOf(row.get("task_status"));
            if (expectedStatus.name().equals(current)) {
                return;
            }
            if (TaskStatus.FAILED.name().equals(current)
                    || TaskStatus.TIMEOUT.name().equals(current)
                    || TaskStatus.CANCELLED.name().equals(current)) {
                throw new AssertionError("Task " + taskId + " ended in " + current + " with error: " + row.get("error_message"));
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

    private record IndexedDocument(Long documentId, Long taskId) {
    }
}
