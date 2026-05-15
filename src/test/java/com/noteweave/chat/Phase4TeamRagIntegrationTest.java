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
import com.noteweave.support.ContainerizedIntegrationTest;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class Phase4TeamRagIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentProcessingService documentProcessingService;

    @Test
    void viewerShouldAskWithinKnowledgeBaseScopeAndGetCitations() throws Exception {
        String ownerToken = registerAndGetToken("phase4_owner_" + System.nanoTime());
        String viewerName = "phase4_viewer_" + System.nanoTime();
        String viewerToken = registerAndGetToken(viewerName);

        Long spaceId = createTeamSpace(ownerToken, "phase4-team-" + System.nanoTime());
        addMember(ownerToken, spaceId, viewerName + "@example.com", "VIEWER");
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase4-kb-" + System.nanoTime());

        IndexedDocument indexedDocument = uploadAndProcess(
                ownerToken,
                spaceId,
                kbId,
                "deploy.txt",
                "text/plain",
                "Prepare a blue-green environment before deployment. Rollback should be rehearsed before production release."
                        .getBytes(StandardCharsets.UTF_8)
        );

        Long sessionId = createChatSession(viewerToken, spaceId, "deployment q&a", "KNOWLEDGE_BASE", new long[]{kbId});

        MvcResult askResult = mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"What is the rollback requirement for this project?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").isNotEmpty())
                .andExpect(jsonPath("$.data.citations[0].sourceType").value("DOCUMENT"))
                .andExpect(jsonPath("$.data.citations[0].sourceId").value(indexedDocument.documentId()))
                .andExpect(jsonPath("$.data.citations[0].snapshotObjectKey").value(org.hamcrest.Matchers.containsString("/citations/")))
                .andReturn();

        JsonNode askJson = objectMapper.readTree(askResult.getResponse().getContentAsString()).path("data");
        Long assistantMessageId = askJson.path("assistantMessageId").asLong();

        mockMvc.perform(get("/api/v1/chat/messages/{messageId}/citations", assistantMessageId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].messageId").value(assistantMessageId))
                .andExpect(jsonPath("$.data[0].quoteText").value(org.hamcrest.Matchers.containsString("Rollback")))
                .andExpect(jsonPath("$.data[0].pageNo").exists())
                .andExpect(jsonPath("$.data[0].quoteHash").isNotEmpty());

        mockMvc.perform(post("/api/v1/chat/messages/{messageId}/feedback", assistantMessageId)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":"HELPFUL","reason":"grounded","comment":"answer is grounded"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rating").value("HELPFUL"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from chat_message where session_id = ?", Integer.class, sessionId))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from citation where source_id = ?", Integer.class, indexedDocument.documentId()))
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from message_citation where message_id = ?", Integer.class, assistantMessageId))
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from retrieval_trace where session_id = ?", Integer.class, sessionId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from llm_call_log where session_id = ? and success = b'1'", Integer.class, sessionId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from answer_feedback where message_id = ?", Integer.class, assistantMessageId))
                .isEqualTo(1);
    }

    @Test
    void shouldNotRecallDocumentsFromOtherSpaceAndShouldFallbackWhenEvidenceMissing() throws Exception {
        String ownerToken = registerAndGetToken("phase4_dual_owner_" + System.nanoTime());

        Long spaceA = createTeamSpace(ownerToken, "phase4-a-" + System.nanoTime());
        Long kbA = createKnowledgeBase(ownerToken, spaceA, "phase4-kb-a-" + System.nanoTime());
        uploadAndProcess(ownerToken, spaceA, kbA, "a.txt", "text/plain",
                "This document talks about release windows and alerting.".getBytes(StandardCharsets.UTF_8));

        Long spaceB = createTeamSpace(ownerToken, "phase4-b-" + System.nanoTime());
        Long kbB = createKnowledgeBase(ownerToken, spaceB, "phase4-kb-b-" + System.nanoTime());
        uploadAndProcess(ownerToken, spaceB, kbB, "b.txt", "text/plain",
                "secret keyword only exists in another space.".getBytes(StandardCharsets.UTF_8));

        Long sessionId = createChatSession(ownerToken, spaceA, "isolation verification", "KNOWLEDGE_BASE", new long[]{kbA});

        mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"secret keyword"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("暂无相关信息")))
                .andExpect(jsonPath("$.data.citations").isEmpty());
    }

    @Test
    void shouldFallbackWhenKnowledgeBaseScopeWasArchivedAfterSessionCreation() throws Exception {
        String ownerToken = registerAndGetToken("phase4_archive_owner_" + System.nanoTime());

        Long spaceId = createTeamSpace(ownerToken, "phase4-archive-space-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase4-archive-kb-" + System.nanoTime());
        uploadAndProcess(ownerToken, spaceId, kbId, "archive.txt", "text/plain",
                "Rollback must be rehearsed before release.".getBytes(StandardCharsets.UTF_8));

        Long sessionId = createChatSession(ownerToken, spaceId, "archive-session", "KNOWLEDGE_BASE", new long[]{kbId});

        mockMvc.perform(delete("/api/v1/team/knowledge-bases/{kbId}", kbId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"What are the rollback requirements?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("暂无相关信息")))
                .andExpect(jsonPath("$.data.citations").isEmpty());
    }

    @Test
    void nonMemberShouldNotCreateSessionOrReadMessageCitations() throws Exception {
        String ownerToken = registerAndGetToken("phase4_owner_denied_" + System.nanoTime());
        String outsiderToken = registerAndGetToken("phase4_outsider_" + System.nanoTime());

        Long spaceId = createTeamSpace(ownerToken, "phase4-denied-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase4-denied-kb-" + System.nanoTime());
        IndexedDocument indexedDocument = uploadAndProcess(ownerToken, spaceId, kbId, "deny.txt", "text/plain",
                "Permission verification document containing citation evidence.".getBytes(StandardCharsets.UTF_8));
        Long sessionId = createChatSession(ownerToken, spaceId, "permission verification", "KNOWLEDGE_BASE", new long[]{kbId});

        MvcResult askResult = mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"What is citation evidence?"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        Long assistantMessageId = objectMapper.readTree(askResult.getResponse().getContentAsString())
                .path("data")
                .path("assistantMessageId")
                .asLong();

        mockMvc.perform(post("/api/v1/chat/sessions")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"spaceId":%d,"sessionType":"TEAM_CHAT","title":"illegal session","scopeType":"KNOWLEDGE_BASE","scopeIds":[%d]}
                                """.formatted(spaceId, kbId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SPACE_ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/chat/messages/{messageId}/citations", assistantMessageId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SPACE_ACCESS_DENIED"));

        assertThat(indexedDocument.documentId()).isNotNull();
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

    private void addMember(String ownerToken, Long spaceId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/members", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"%s"}
                                """.formatted(email, role)))
                .andExpect(status().isOk());
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
                                {"name":"%s","description":"phase4 kb"}
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
                                {"name":"%s","description":"phase4 team"}
                                """.formatted(spaceName)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private String registerAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s@example.com","password":"Password123!","displayName":"Phase4 User"}
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
}
