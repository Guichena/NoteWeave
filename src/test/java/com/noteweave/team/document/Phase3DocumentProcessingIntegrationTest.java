package com.noteweave.team.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
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
class Phase3DocumentProcessingIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentProcessingService documentProcessingService;

    @Test
    void processTxtDocumentShouldPersistParsedTextChunksAndSearchWithLifecycleFilters() throws Exception {
        String owner = "phase3_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(owner);
        Long spaceId = createTeamSpace(ownerToken, "phase3-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase3-kb-" + System.nanoTime());

        byte[] content = """
                # Deployment Guide

                Blue green deployment keeps the previous version warm.
                Rollback should be rehearsed before production release.
                """.getBytes(StandardCharsets.UTF_8);
        JsonNode merged = uploadAndMerge(ownerToken, kbId, "deploy.md", "text/markdown", content);
        Long documentId = merged.path("data").path("documentId").asLong();
        Long taskId = merged.path("data").path("taskId").asLong();

        documentProcessingService.process(payload(taskId, documentId, spaceId, kbId, "deploy.md", "text/markdown"));
        documentProcessingService.process(payload(taskId, documentId, spaceId, kbId, "deploy.md", "text/markdown"));

        assertDocumentIndexed(documentId, 1);
        Integer chunkCount = jdbcTemplate.queryForObject(
                "select count(*) from document_chunk where document_id = ? and index_version = 1",
                Integer.class,
                documentId
        );
        assertThat(chunkCount).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select active_index_version from document where id = ?", Integer.class, documentId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select parsed_text_object_key from document where id = ?", String.class, documentId))
                .contains("/parsed-text/document/" + documentId + "/1.txt");
        assertThat(jdbcTemplate.queryForObject("select count(*) from task_attempt where task_id = ?", Integer.class, taskId))
                .isEqualTo(1);

        mockMvc.perform(get("/api/v1/team/knowledge-bases/{knowledgeBaseId}/search", kbId)
                        .param("keyword", "rollback")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].documentId").value(documentId))
                .andExpect(jsonPath("$.data.items[0].content").value(org.hamcrest.Matchers.containsString("Rollback")));

        mockMvc.perform(delete("/api/v1/team/documents/{documentId}", documentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/team/knowledge-bases/{knowledgeBaseId}/search", kbId)
                        .param("keyword", "rollback")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        mockMvc.perform(delete("/api/v1/team/knowledge-bases/{kbId}", kbId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/team/knowledge-bases/{knowledgeBaseId}/search", kbId)
                        .param("keyword", "rollback")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void emptyParsedTextShouldFailTaskAndDocument() throws Exception {
        String owner = "phase3_fail_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(owner);
        Long spaceId = createTeamSpace(ownerToken, "phase3-fail-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase3-fail-kb-" + System.nanoTime());

        byte[] content = "   \n\t  ".getBytes(StandardCharsets.UTF_8);
        JsonNode merged = uploadAndMerge(
                ownerToken,
                kbId,
                "empty.txt",
                "text/plain",
                content
        );
        Long documentId = merged.path("data").path("documentId").asLong();
        Long taskId = merged.path("data").path("taskId").asLong();

        assertThatThrownBy(() -> documentProcessingService.process(payload(
                        taskId,
                        documentId,
                        spaceId,
                        kbId,
                        "empty.txt",
                        "text/plain"
                )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("empty");

        assertThat(jdbcTemplate.queryForObject("select status from document where id = ?", String.class, documentId))
                .isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("select task_status from task where id = ?", String.class, taskId))
                .isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("select error_message from task where id = ?", String.class, taskId))
                .contains("empty");
        assertThat(jdbcTemplate.queryForObject("select active_index_version from document where id = ?", Integer.class, documentId))
                .isZero();
    }

    @Test
    void initUploadShouldRejectUnsupportedDocx() throws Exception {
        String owner = "phase3_docx_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(owner);
        Long spaceId = createTeamSpace(ownerToken, "phase3-docx-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase3-docx-kb-" + System.nanoTime());

        byte[] content = "fake docx".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> init = new HashMap<>();
        init.put("fileMd5", md5Hex(content));
        init.put("fileName", "unsupported.docx");
        init.put("contentType", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        init.put("totalSize", content.length);
        init.put("chunkSize", content.length);
        init.put("totalChunks", 1);

        mockMvc.perform(post("/api/v1/team/knowledge-bases/{knowledgeBaseId}/documents/uploads/init", kbId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(init)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_DOCUMENT_TYPE"));

        init.put("contentType", "text/plain");
        mockMvc.perform(post("/api/v1/team/knowledge-bases/{knowledgeBaseId}/documents/uploads/init", kbId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(init)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_DOCUMENT_TYPE"));
    }

    @Test
    void reindexShouldCreateNewVersionAndSwitchActiveOnlyAfterSuccess() throws Exception {
        String owner = "phase3_reindex_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(owner);
        Long spaceId = createTeamSpace(ownerToken, "phase3-reindex-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase3-reindex-kb-" + System.nanoTime());

        byte[] content = "Phase three reindex keeps the old active version until the new one succeeds.".getBytes(StandardCharsets.UTF_8);
        JsonNode merged = uploadAndMerge(ownerToken, kbId, "reindex.txt", "text/plain", content);
        Long documentId = merged.path("data").path("documentId").asLong();
        Long taskId = merged.path("data").path("taskId").asLong();

        documentProcessingService.process(payload(taskId, documentId, spaceId, kbId, "reindex.txt", "text/plain"));
        assertDocumentIndexed(documentId, 1);

        MvcResult reindexResult = mockMvc.perform(post("/api/v1/team/documents/{documentId}/reindex", documentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("PENDING"))
                .andReturn();
        Long reindexTaskId = objectMapper.readTree(reindexResult.getResponse().getContentAsString()).path("data").path("id").asLong();
        assertThat(jdbcTemplate.queryForObject("select active_index_version from document where id = ?", Integer.class, documentId))
                .isEqualTo(1);

        documentProcessingService.process(payload(reindexTaskId, documentId, spaceId, kbId, "reindex.txt", "text/plain"));
        assertDocumentIndexed(documentId, 2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from document_chunk where document_id = ? and index_version in (1, 2)",
                Integer.class,
                documentId
        )).isGreaterThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from task_attempt where task_id = ?", Integer.class, reindexTaskId))
                .isEqualTo(1);
    }

    @Test
    void failedReindexShouldKeepOldActiveVersionSearchable() throws Exception {
        String owner = "phase3_reindex_fail_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(owner);
        Long spaceId = createTeamSpace(ownerToken, "phase3-reindex-fail-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase3-reindex-fail-kb-" + System.nanoTime());

        byte[] content = "Stable rollback marker should remain searchable after a failed reindex.".getBytes(StandardCharsets.UTF_8);
        JsonNode merged = uploadAndMerge(ownerToken, kbId, "stable.txt", "text/plain", content);
        Long documentId = merged.path("data").path("documentId").asLong();
        Long taskId = merged.path("data").path("taskId").asLong();

        documentProcessingService.process(payload(taskId, documentId, spaceId, kbId, "stable.txt", "text/plain"));
        assertDocumentIndexed(documentId, 1);

        jdbcTemplate.update("update file_object set content_type = ? where id = (select file_object_id from document where id = ?)",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                documentId);
        jdbcTemplate.update("update document set original_filename = ? where id = ?", "stable.docx", documentId);
        Long reindexTaskId = createReindexTask(ownerToken, documentId);
        assertThatThrownBy(() -> documentProcessingService.process(payload(
                        reindexTaskId,
                        documentId,
                        spaceId,
                        kbId,
                        "stable.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported");

        assertDocumentIndexed(documentId, 1);
        assertThat(jdbcTemplate.queryForObject("select task_status from task where id = ?", String.class, reindexTaskId))
                .isEqualTo("FAILED");

        mockMvc.perform(get("/api/v1/team/knowledge-bases/{knowledgeBaseId}/search", kbId)
                        .param("keyword", "rollback")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].documentId").value(documentId));
    }

    private void assertDocumentIndexed(Long documentId, int activeVersion) {
        String documentError = jdbcTemplate.queryForObject("select error_message from document where id = ?", String.class, documentId);
        assertThat(jdbcTemplate.queryForObject("select status from document where id = ?", String.class, documentId))
                .describedAs("document error: %s", documentError)
                .isEqualTo("INDEXED");
        assertThat(jdbcTemplate.queryForObject("select parse_status from document where id = ?", String.class, documentId))
                .isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject("select index_status from document where id = ?", String.class, documentId))
                .isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject("select active_index_version from document where id = ?", Integer.class, documentId))
                .isEqualTo(activeVersion);
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

    private Long createReindexTask(String token, Long documentId) throws Exception {
        MvcResult reindexResult = mockMvc.perform(post("/api/v1/team/documents/{documentId}/reindex", documentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(reindexResult.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private Long createKnowledgeBase(String token, Long spaceId, String name) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", name);
        payload.put("description", "phase3 kb");
        MvcResult result = mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/knowledge-bases", spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private Long createTeamSpace(String token, String spaceName) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", spaceName);
        payload.put("description", "team for phase3");
        MvcResult result = mockMvc.perform(post("/api/v1/spaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private String registerAndGetToken(String username) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("email", username + "@example.com");
        payload.put("password", "Password123!");
        payload.put("displayName", "Phase3 User");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
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
}
