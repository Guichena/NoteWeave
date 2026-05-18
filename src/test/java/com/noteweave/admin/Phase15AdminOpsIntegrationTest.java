package com.noteweave.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.admin.service.AdminStorageSupport;
import com.noteweave.storage.service.FileStorageService;
import com.noteweave.support.ContainerizedIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Phase15AdminOpsIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private AdminStorageSupport adminStorageSupport;

    @Test
    void adminShouldManageUsersSpacesTasksCleanupHealthAndAudit() throws Exception {
        String adminUsername = "phase15_admin_" + System.nanoTime();
        register(adminUsername);
        jdbcTemplate.update("update users set system_role = 'ADMIN' where username = ?", adminUsername);
        String adminToken = login(adminUsername, "Password123!");

        String victimUsername = "phase15_victim_" + System.nanoTime();
        register(victimUsername);

        String ownerUsername = "phase15_owner_" + System.nanoTime();
        String ownerToken = register(ownerUsername);
        Long spaceId = createTeamSpace(ownerToken, "phase15-space-" + System.nanoTime());
        Long knowledgeBaseId = createKnowledgeBase(ownerToken, spaceId, "phase15-kb-" + System.nanoTime());

        JsonNode mergedTask = uploadAndMerge(ownerToken, knowledgeBaseId, "guide.txt", "text/plain",
                "Runbooks should be rehearsed before release.".getBytes(StandardCharsets.UTF_8));
        Long pendingTaskId = mergedTask.path("data").path("taskId").asLong();
        Long documentId = mergedTask.path("data").path("documentId").asLong();

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").exists());

        mockMvc.perform(post("/api/v1/admin/users/{userId}/disable",
                        jdbcTemplate.queryForObject("select id from users where username = ?", Long.class, victimUsername))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usernameOrEmail":"%s","password":"Password123!"}
                                """.formatted(victimUsername)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        Long victimUserId = jdbcTemplate.queryForObject("select id from users where username = ?", Long.class, victimUsername);
        mockMvc.perform(post("/api/v1/admin/users/{userId}/enable", victimUserId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/admin/spaces")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/v1/admin/spaces/{spaceId}", spaceId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.space.id").value(spaceId))
                .andExpect(jsonPath("$.data.space.documentCount").value(1));

        mockMvc.perform(post("/api/v1/admin/tasks/{taskId}/cancel", pendingTaskId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("CANCELLED"));

        JsonNode retryCandidate = uploadAndMerge(ownerToken, knowledgeBaseId, "retry.txt", "text/plain",
                "Retry should verify source objects still exist.".getBytes(StandardCharsets.UTF_8));
        Long retryTaskId = retryCandidate.path("data").path("taskId").asLong();
        jdbcTemplate.update("update task set task_status = 'FAILED', error_message = 'synthetic failure', finished_at = now() where id = ?", retryTaskId);

        mockMvc.perform(post("/api/v1/admin/tasks/{taskId}/retry", retryTaskId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.retryCount").value(1));

        JsonNode expiringUpload = initUpload(ownerToken, knowledgeBaseId, "cleanup.txt", "text/plain",
                "Cleanup residue".getBytes(StandardCharsets.UTF_8));
        Long uploadId = expiringUpload.path("data").path("uploadId").asLong();
        MockMultipartFile chunk = new MockMultipartFile("file", "chunk-0.bin", "text/plain", "Cleanup residue".getBytes(StandardCharsets.UTF_8));
        JsonNode uploadedChunk = uploadChunk(ownerToken, uploadId, chunk);
        jdbcTemplate.update("update document_upload set expires_at = ? where id = ?", LocalDateTime.now().minusHours(25), uploadId);
        String chunkObjectKey = jdbcTemplate.queryForObject("select object_key from upload_chunk where upload_id = ?", String.class, uploadId);
        assertThat(fileStorageService.objectExists(adminStorageSupport.currentBucket(), chunkObjectKey)).isTrue();

        JsonNode scan = readJson(mockMvc.perform(post("/api/v1/admin/cleanup/scan")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobType":"UPLOAD_EXPIRED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andReturn());
        long cleanupJobId = scan.path("data").path("id").asLong();

        mockMvc.perform(post("/api/v1/admin/cleanup/execute")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobId":%d}
                                """.formatted(cleanupJobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cleanupCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        assertThat(jdbcTemplate.queryForObject("select status from document_upload where id = ?", String.class, uploadId)).isEqualTo("EXPIRED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from upload_chunk where upload_id = ?", Integer.class, uploadId)).isZero();
        assertThat(fileStorageService.objectExists(adminStorageSupport.currentBucket(), chunkObjectKey)).isFalse();

        mockMvc.perform(get("/api/v1/admin/health")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.components.length()").value(6));

        mockMvc.perform(get("/api/v1/admin/dashboard/summary")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.documentCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].action").exists());

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action in ('TASK_RETRY', 'RESOURCE_CLEANUP', 'USER_DISABLE')",
                Integer.class
        )).isGreaterThanOrEqualTo(3);
        assertThat(documentId).isGreaterThan(0L);
        assertThat(uploadedChunk.path("data").path("uploaded").asBoolean()).isTrue();
    }

    private String register(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s@example.com","password":"Password123!","displayName":"Phase15 User"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("accessToken").asText();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usernameOrEmail":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("accessToken").asText();
    }

    private Long createTeamSpace(String token, String spaceName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/spaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"phase15 team"}
                                """.formatted(spaceName)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private Long createKnowledgeBase(String token, Long spaceId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/knowledge-bases", spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"phase15 kb"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private JsonNode initUpload(String token, Long kbId, String fileName, String contentType, byte[] content) throws Exception {
        Map<String, Object> init = new HashMap<>();
        init.put("fileMd5", md5Hex(content));
        init.put("fileName", fileName);
        init.put("contentType", contentType);
        init.put("totalSize", content.length);
        init.put("chunkSize", content.length);
        init.put("totalChunks", 1);
        MvcResult result = mockMvc.perform(post("/api/v1/team/knowledge-bases/{knowledgeBaseId}/documents/uploads/init", kbId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(init)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result);
    }

    private JsonNode uploadChunk(String token, Long uploadId, MockMultipartFile chunk) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/team/document-uploads/{uploadId}/chunks", uploadId)
                        .file(chunk)
                        .param("chunkIndex", "0")
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        })
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result);
    }

    private JsonNode uploadAndMerge(String token, Long kbId, String fileName, String contentType, byte[] content) throws Exception {
        JsonNode init = initUpload(token, kbId, fileName, contentType, content);
        Long uploadId = init.path("data").path("uploadId").asLong();
        uploadChunk(token, uploadId, new MockMultipartFile("file", "chunk-0.bin", contentType, content));
        MvcResult mergeResult = mockMvc.perform(post("/api/v1/team/document-uploads/{uploadId}/merge", uploadId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(mergeResult);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
