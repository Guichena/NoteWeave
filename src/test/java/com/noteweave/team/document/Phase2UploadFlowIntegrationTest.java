package com.noteweave.team.document;

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
import com.noteweave.storage.service.FileStorageService;
import com.noteweave.task.service.TaskDispatcher;
import com.noteweave.team.document.dto.DocumentProcessTaskPayload;
import com.noteweave.team.document.service.UploadCleanupService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class Phase2UploadFlowIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskDispatcher taskDispatcher;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private UploadCleanupService uploadCleanupService;

    @Value("${noteweave.storage.paths.test-run-id:}")
    private String testRunId;

    @Value("${spring.kafka.bootstrap-servers}")
    private String kafkaBootstrapServers;

    @Value("${noteweave.kafka.topics.document-process}")
    private String documentTopic;

    @Test
    void viewerShouldNotInitUpload() throws Exception {
        String owner = "phase2_owner_" + System.nanoTime();
        String viewer = "phase2_viewer_" + System.nanoTime();
        String ownerToken = registerAndGetToken(owner);
        String viewerToken = registerAndGetToken(viewer);

        Long spaceId = createTeamSpace(ownerToken, "phase2-team-" + System.nanoTime());
        addMember(ownerToken, spaceId, viewer + "@example.com", "VIEWER");
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase2-kb-" + System.nanoTime());

        Map<String, Object> payload = new HashMap<>();
        payload.put("fileMd5", "d41d8cd98f00b204e9800998ecf8427e");
        payload.put("fileName", "design.pdf");
        payload.put("contentType", "application/pdf");
        payload.put("totalSize", 12);
        payload.put("chunkSize", 12);
        payload.put("totalChunks", 1);

        mockMvc.perform(post("/api/v1/team/knowledge-bases/{knowledgeBaseId}/documents/uploads/init", kbId)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("SPACE_ACCESS_DENIED"));
    }

    @Test
    void deleteKnowledgeBaseShouldArchiveInsteadOfPhysicalDelete() throws Exception {
        String owner = "phase2_kb_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(owner);
        long ownerId = getCurrentUserId(ownerToken);

        Long spaceId = createTeamSpace(ownerToken, "phase2-kb-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase2-kb-" + System.nanoTime());

        mockMvc.perform(delete("/api/v1/team/knowledge-bases/{kbId}", kbId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Integer totalRows = jdbcTemplate.queryForObject(
                "select count(*) from knowledge_base where id = ?",
                Integer.class,
                kbId
        );
        String statusValue = jdbcTemplate.queryForObject(
                "select status from knowledge_base where id = ?",
                String.class,
                kbId
        );
        Long deletedBy = jdbcTemplate.queryForObject(
                "select deleted_by from knowledge_base where id = ?",
                Long.class,
                kbId
        );
        Integer activeRows = jdbcTemplate.queryForObject(
                "select count(*) from knowledge_base where id = ? and status = 'ACTIVE'",
                Integer.class,
                kbId
        );

        assertThat(totalRows).isEqualTo(1);
        assertThat(statusValue).isEqualTo("ARCHIVED");
        assertThat(deletedBy).isEqualTo(ownerId);
        assertThat(activeRows).isEqualTo(0);
    }

    @Test
    void mergeShouldBeIdempotentAndNotCreateDuplicateDocumentTaskOrOutbox() throws Exception {
        String owner = "phase2_merge_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(owner);

        Long spaceId = createTeamSpace(ownerToken, "phase2-merge-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase2-merge-kb-" + System.nanoTime());

        byte[] content = "hello noteweave".getBytes(StandardCharsets.UTF_8);
        String fileMd5 = md5Hex(content);
        Long uploadId = initUpload(ownerToken, kbId, fileMd5, "hello.txt", content.length, content.length, 1);
        uploadChunk(ownerToken, uploadId, 0, content);

        JsonNode firstMerge = mergeUpload(ownerToken, uploadId);
        Long firstDocumentId = firstMerge.path("data").path("documentId").asLong();
        Long firstTaskId = firstMerge.path("data").path("taskId").asLong();
        String firstStatus = firstMerge.path("data").path("status").asText();

        dispatchPendingOutboxBySchedulerOrManually();

        DocumentProcessTaskPayload payload = readDocumentProcessPayload(firstTaskId);
        assertThat(payload.getTaskId()).isEqualTo(firstTaskId);
        assertThat(payload.getDocumentId()).isEqualTo(firstDocumentId);

        JsonNode secondMerge = mergeUpload(ownerToken, uploadId);
        Long secondDocumentId = secondMerge.path("data").path("documentId").asLong();
        Long secondTaskId = secondMerge.path("data").path("taskId").asLong();
        String secondStatus = secondMerge.path("data").path("status").asText();

        Integer documentCount = jdbcTemplate.queryForObject(
                "select count(*) from document where id = ?",
                Integer.class,
                firstDocumentId
        );
        Integer taskCount = jdbcTemplate.queryForObject(
                "select count(*) from task where target_type = 'DOCUMENT' and target_id = ?",
                Integer.class,
                firstDocumentId
        );
        Integer outboxCount = jdbcTemplate.queryForObject(
                "select count(*) from task_outbox where task_id = ?",
                Integer.class,
                firstTaskId
        );
        String outboxStatus = jdbcTemplate.queryForObject(
                "select status from task_outbox where task_id = ? order by id desc limit 1",
                String.class,
                firstTaskId
        );

        assertThat(secondDocumentId).isEqualTo(firstDocumentId);
        assertThat(secondTaskId).isEqualTo(firstTaskId);
        assertThat(secondStatus).isEqualTo(firstStatus);
        assertThat(documentCount).isEqualTo(1);
        assertThat(taskCount).isEqualTo(1);
        assertThat(outboxCount).isEqualTo(1);
        assertThat(outboxStatus).isEqualTo("SENT");
    }

    @Test
    void initUploadShouldInstantReuseFileObjectWithinSameSpaceButCreateNewDocumentAndTask() throws Exception {
        String owner = "phase2_instant_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(owner);

        Long spaceId = createTeamSpace(ownerToken, "phase2-instant-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase2-instant-kb-" + System.nanoTime());

        byte[] content = "instant upload candidate".getBytes(StandardCharsets.UTF_8);
        String fileMd5 = md5Hex(content);

        Long firstUploadId = initUpload(ownerToken, kbId, fileMd5, "first.txt", content.length, content.length, 1);
        uploadChunk(ownerToken, firstUploadId, 0, content);
        JsonNode firstMerge = mergeUpload(ownerToken, firstUploadId);
        Long firstDocumentId = firstMerge.path("data").path("documentId").asLong();

        Integer refCountAfterFirstMerge = jdbcTemplate.queryForObject(
                "select ref_count from file_object where id = (select file_object_id from document where id = ?)",
                Integer.class,
                firstDocumentId
        );
        assertThat(refCountAfterFirstMerge).isEqualTo(1);

        Map<String, Object> secondInitPayload = new HashMap<>();
        secondInitPayload.put("fileMd5", fileMd5);
        secondInitPayload.put("fileName", "second.txt");
        secondInitPayload.put("contentType", "application/octet-stream");
        secondInitPayload.put("totalSize", content.length);
        secondInitPayload.put("chunkSize", content.length);
        secondInitPayload.put("totalChunks", 1);

        MvcResult secondInitResult = mockMvc.perform(post("/api/v1/team/knowledge-bases/{knowledgeBaseId}/documents/uploads/init", kbId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondInitPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instantUpload").value(true))
                .andReturn();

        JsonNode secondInit = objectMapper.readTree(secondInitResult.getResponse().getContentAsString());
        Long secondUploadId = secondInit.path("data").path("uploadId").asLong();
        Long secondDocumentId = secondInit.path("data").path("documentId").asLong();
        assertThat(secondUploadId).isNotNull();
        assertThat(secondDocumentId).isNotNull();
        assertThat(secondDocumentId).isNotEqualTo(firstDocumentId);

        String uploadStatus = jdbcTemplate.queryForObject(
                "select status from document_upload where id = ?",
                String.class,
                secondUploadId
        );
        Long secondTaskId = jdbcTemplate.queryForObject(
                "select task_id from document_upload where id = ?",
                Long.class,
                secondUploadId
        );
        Long firstFileObjectId = jdbcTemplate.queryForObject(
                "select file_object_id from document where id = ?",
                Long.class,
                firstDocumentId
        );
        Long secondFileObjectId = jdbcTemplate.queryForObject(
                "select file_object_id from document where id = ?",
                Long.class,
                secondDocumentId
        );
        Integer refCountAfterInstant = jdbcTemplate.queryForObject(
                "select ref_count from file_object where id = ?",
                Integer.class,
                firstFileObjectId
        );
        String secondTaskStatus = jdbcTemplate.queryForObject(
                "select task_status from task where id = ?",
                String.class,
                secondTaskId
        );

        assertThat(uploadStatus).isEqualTo("PROCESSING");
        assertThat(secondTaskId).isNotNull();
        assertThat(firstFileObjectId).isEqualTo(secondFileObjectId);
        assertThat(refCountAfterInstant).isEqualTo(2);
        assertThat(secondTaskStatus).isEqualTo("PENDING");
    }

    @Test
    void initUploadShouldNotInstantReuseFileAcrossDifferentSpace() throws Exception {
        String owner = "phase2_cross_space_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(owner);

        Long spaceA = createTeamSpace(ownerToken, "phase2-cross-a-" + System.nanoTime());
        Long kbA = createKnowledgeBase(ownerToken, spaceA, "phase2-cross-kb-a-" + System.nanoTime());
        Long spaceB = createTeamSpace(ownerToken, "phase2-cross-b-" + System.nanoTime());
        Long kbB = createKnowledgeBase(ownerToken, spaceB, "phase2-cross-kb-b-" + System.nanoTime());

        byte[] content = "cross space content".getBytes(StandardCharsets.UTF_8);
        String fileMd5 = md5Hex(content);

        Long uploadA = initUpload(ownerToken, kbA, fileMd5, "space-a.txt", content.length, content.length, 1);
        uploadChunk(ownerToken, uploadA, 0, content);
        mergeUpload(ownerToken, uploadA);

        Map<String, Object> initInSpaceB = new HashMap<>();
        initInSpaceB.put("fileMd5", fileMd5);
        initInSpaceB.put("fileName", "space-b.txt");
        initInSpaceB.put("contentType", "application/octet-stream");
        initInSpaceB.put("totalSize", content.length);
        initInSpaceB.put("chunkSize", content.length);
        initInSpaceB.put("totalChunks", 1);

        mockMvc.perform(post("/api/v1/team/knowledge-bases/{knowledgeBaseId}/documents/uploads/init", kbB)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initInSpaceB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instantUpload").value(false))
                .andExpect(jsonPath("$.data.documentId").doesNotExist());
    }

    @Test
    void uploadChunkAndMergeShouldPersistAndCleanupMinioObjects() throws Exception {
        String owner = "phase2_minio_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(owner);

        Long spaceId = createTeamSpace(ownerToken, "phase2-minio-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase2-minio-kb-" + System.nanoTime());

        byte[] chunk0 = "hello-".getBytes(StandardCharsets.UTF_8);
        byte[] chunk1 = "minio".getBytes(StandardCharsets.UTF_8);
        byte[] whole = concat(chunk0, chunk1);

        Long uploadId = initUpload(ownerToken, kbId, md5Hex(whole), "minio.txt", whole.length, chunk0.length, 2);

        uploadChunk(ownerToken, uploadId, 0, chunk0);
        uploadChunk(ownerToken, uploadId, 1, chunk1);

        List<String> chunkKeys = new ArrayList<>(jdbcTemplate.queryForList(
                "select object_key from upload_chunk where upload_id = ? order by chunk_index",
                String.class,
                uploadId
        ));
        for (String key : chunkKeys) {
            assertThat(fileStorageService.objectExists(currentBucket(), key))
                    .as("chunk object should exist before merge: %s", key)
                    .isTrue();
        }

        JsonNode mergeResponse = mergeUpload(ownerToken, uploadId);
        String objectKey = mergeResponse.path("data").path("objectKey").asText();
        assertThat(objectKey).isNotBlank();
        assertThat(fileStorageService.objectExists(currentBucket(), objectKey)).isTrue();

        for (String key : chunkKeys) {
            assertThat(fileStorageService.objectExists(currentBucket(), key))
                    .as("chunk object should be cleaned after merge: %s", key)
                    .isFalse();
        }
        Integer chunkRowsAfterMerge = jdbcTemplate.queryForObject(
                "select count(*) from upload_chunk where upload_id = ?",
                Integer.class,
                uploadId
        );
        assertThat(chunkRowsAfterMerge).isZero();

        mockMvc.perform(get("/api/v1/team/document-uploads/{uploadId}/status", uploadId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.progress").value(100.0))
                .andExpect(jsonPath("$.data.uploadedChunks[0]").value(0))
                .andExpect(jsonPath("$.data.uploadedChunks[1]").value(1));
    }

    @Test
    void cancelUploadShouldCleanupChunkObjectsAndBitmapOnly() throws Exception {
        String owner = "phase2_cancel_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(owner);

        Long spaceId = createTeamSpace(ownerToken, "phase2-cancel-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase2-cancel-kb-" + System.nanoTime());

        byte[] chunk0 = "chunk-0".getBytes(StandardCharsets.UTF_8);
        byte[] chunk1 = "chunk-1".getBytes(StandardCharsets.UTF_8);
        byte[] whole = concat(chunk0, chunk1);

        Long uploadId = initUpload(ownerToken, kbId, md5Hex(whole), "cancel.txt", whole.length, chunk0.length, 2);
        uploadChunk(ownerToken, uploadId, 0, chunk0);

        String chunkKey = jdbcTemplate.queryForObject(
                "select object_key from upload_chunk where upload_id = ? and chunk_index = 0",
                String.class,
                uploadId
        );
        assertThat(fileStorageService.objectExists(currentBucket(), chunkKey)).isTrue();

        mockMvc.perform(post("/api/v1/team/document-uploads/{uploadId}/cancel", uploadId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(fileStorageService.objectExists(currentBucket(), chunkKey)).isFalse();
        Integer chunkRowsAfterCancel = jdbcTemplate.queryForObject(
                "select count(*) from upload_chunk where upload_id = ?",
                Integer.class,
                uploadId
        );
        assertThat(chunkRowsAfterCancel).isZero();
    }

    @Test
    void deleteDocumentShouldBeSoftDeleteAndMustNotDecreaseRefCount() throws Exception {
        String owner = "phase2_doc_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(owner);

        Long spaceId = createTeamSpace(ownerToken, "phase2-doc-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase2-doc-kb-" + System.nanoTime());

        byte[] content = "phase2 document".getBytes(StandardCharsets.UTF_8);
        String fileMd5 = md5Hex(content);
        Long uploadId = initUpload(ownerToken, kbId, fileMd5, "phase2.txt", content.length, content.length, 1);
        uploadChunk(ownerToken, uploadId, 0, content);
        JsonNode mergeResponse = mergeUpload(ownerToken, uploadId);
        Long documentId = mergeResponse.path("data").path("documentId").asLong();

        Long fileObjectId = jdbcTemplate.queryForObject(
                "select file_object_id from document where id = ?",
                Long.class,
                documentId
        );
        Integer refCountBeforeDelete = jdbcTemplate.queryForObject(
                "select ref_count from file_object where id = ?",
                Integer.class,
                fileObjectId
        );

        mockMvc.perform(delete("/api/v1/team/documents/{documentId}", documentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Integer refCountAfterDelete = jdbcTemplate.queryForObject(
                "select ref_count from file_object where id = ?",
                Integer.class,
                fileObjectId
        );
        String documentStatus = jdbcTemplate.queryForObject(
                "select status from document where id = ?",
                String.class,
                documentId
        );
        Integer visibleDocumentCount = jdbcTemplate.queryForObject(
                "select count(*) from document where knowledge_base_id = ? and deleted_at is null and status <> 'DELETED'",
                Integer.class,
                kbId
        );

        assertThat(refCountBeforeDelete).isEqualTo(1);
        assertThat(refCountAfterDelete).isEqualTo(1);
        assertThat(documentStatus).isEqualTo("DELETED");
        assertThat(visibleDocumentCount).isEqualTo(0);
    }

    @Test
    void cleanupExpiredUploadShouldDeleteOnlyTempChunksAndBitmap() throws Exception {
        String owner = "phase2_expire_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(owner);

        Long spaceId = createTeamSpace(ownerToken, "phase2-expire-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase2-expire-kb-" + System.nanoTime());

        byte[] chunk0 = "expire-0".getBytes(StandardCharsets.UTF_8);
        byte[] chunk1 = "expire-1".getBytes(StandardCharsets.UTF_8);
        byte[] whole = concat(chunk0, chunk1);

        Long uploadId = initUpload(ownerToken, kbId, md5Hex(whole), "expire.txt", whole.length, chunk0.length, 2);
        uploadChunk(ownerToken, uploadId, 0, chunk0);
        uploadChunk(ownerToken, uploadId, 1, chunk1);

        List<String> chunkKeys = new ArrayList<>(jdbcTemplate.queryForList(
                "select object_key from upload_chunk where upload_id = ? order by chunk_index",
                String.class,
                uploadId
        ));
        assertThat(chunkKeys).hasSize(2);
        assertThat(fileStorageService.objectExists(currentBucket(), chunkKeys.get(0))).isTrue();
        assertThat(fileStorageService.objectExists(currentBucket(), chunkKeys.get(1))).isTrue();

        jdbcTemplate.update(
                "update document_upload set status = 'UPLOADING', expires_at = ? where id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(10)),
                uploadId
        );

        int cleaned = uploadCleanupService.cleanupExpiredUploads(LocalDateTime.now());
        assertThat(cleaned).isGreaterThanOrEqualTo(1);

        String statusValue = jdbcTemplate.queryForObject(
                "select status from document_upload where id = ?",
                String.class,
                uploadId
        );
        assertThat(statusValue).isEqualTo("EXPIRED");
        assertThat(fileStorageService.objectExists(currentBucket(), chunkKeys.get(0))).isFalse();
        assertThat(fileStorageService.objectExists(currentBucket(), chunkKeys.get(1))).isFalse();
        Integer chunkRowsAfterCleanup = jdbcTemplate.queryForObject(
                "select count(*) from upload_chunk where upload_id = ?",
                Integer.class,
                uploadId
        );
        assertThat(chunkRowsAfterCleanup).isZero();
    }

    private void dispatchPendingOutboxBySchedulerOrManually() {
        taskDispatcher.dispatchPendingMessages();
    }

    private DocumentProcessTaskPayload readDocumentProcessPayload(Long taskId) throws Exception {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "phase2-document-payload-" + System.nanoTime());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(Collections.singletonList(documentTopic));
            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline) {
                var records = consumer.poll(Duration.ofMillis(200));
                for (var record : records) {
                    if (String.valueOf(taskId).equals(record.key())) {
                        return objectMapper.readValue(record.value(), DocumentProcessTaskPayload.class);
                    }
                }
            }
        }
        throw new AssertionError("Timed out waiting for document process kafka payload for task " + taskId);
    }

    private Long createKnowledgeBase(String token, Long spaceId, String name) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", name);
        payload.put("description", "phase2 kb");

        MvcResult result = mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/knowledge-bases", spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
    }

    private Long initUpload(
            String token,
            Long knowledgeBaseId,
            String fileMd5,
            String fileName,
            int totalSize,
            int chunkSize,
            int totalChunks
    ) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fileMd5", fileMd5);
        payload.put("fileName", fileName);
        payload.put("contentType", "application/octet-stream");
        payload.put("totalSize", totalSize);
        payload.put("chunkSize", chunkSize);
        payload.put("totalChunks", totalChunks);

        MvcResult result = mockMvc.perform(post("/api/v1/team/knowledge-bases/{knowledgeBaseId}/documents/uploads/init", knowledgeBaseId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("uploadId").asLong();
    }

    private void uploadChunk(String token, Long uploadId, int chunkIndex, byte[] content) throws Exception {
        MockMultipartFile chunk = new MockMultipartFile(
                "file",
                "chunk-" + chunkIndex + ".bin",
                "application/octet-stream",
                content
        );
        mockMvc.perform(multipart("/api/v1/team/document-uploads/{uploadId}/chunks", uploadId)
                        .file(chunk)
                        .param("chunkIndex", String.valueOf(chunkIndex))
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        })
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private JsonNode mergeUpload(String token, Long uploadId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/team/document-uploads/{uploadId}/merge", uploadId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Long createTeamSpace(String token, String spaceName) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", spaceName);
        payload.put("description", "team for phase2");
        MvcResult result = mockMvc.perform(post("/api/v1/spaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
    }

    private long getCurrentUserId(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
    }

    private void addMember(String ownerToken, Long spaceId, String email, String role) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("email", email);
        payload.put("role", role);
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/members", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());
    }

    private String registerAndGetToken(String username) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("email", username + "@example.com");
        payload.put("password", "Password123!");
        payload.put("displayName", "Phase2 User");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("accessToken").asText();
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

    private byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private String currentBucket() {
        return (testRunId != null && !testRunId.isBlank())
                ? fileStorageService.testBucket()
                : fileStorageService.devBucket();
    }

}
