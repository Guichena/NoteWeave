package com.noteweave.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskCommandPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "noteweave.elasticsearch.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadResumeInstantContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectStorage storage;
    @Autowired TaskCommandPort tasks;

    private String workspace() throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v2/workspaces").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "上传验证", "description", "续传与哈希"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("workspace_id").asText();
    }

    private Map<String, Object> request(int size, int chunks, String hash) {
        Map<String, Object> value = new LinkedHashMap<>(Map.of("file_name", "资料.txt", "file_size", size,
                "mime_type", "text/plain", "chunk_size", size, "total_chunks", chunks));
        if (hash != null) value.put("sha256", hash);
        return value;
    }

    private JsonNode create(String workspace, Map<String, Object> request) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v2/workspaces/{id}/uploads", workspace).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private void chunk(String upload, int index, byte[] bytes) throws Exception {
        mvc.perform(put("/api/v2/uploads/{id}/chunks/{index}", upload, index).content(bytes))
                .andExpect(status().isOk());
    }

    private JsonNode complete(String upload) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v2/uploads/{id}/complete", upload)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    private String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void queryListsReceivedIndexesInOrderAndReplacingChunkDoesNotDuplicateIt() throws Exception {
        String upload = create(workspace(), request(6, 3, null)).path("upload_id").asText();
        chunk(upload, 2, "ef".getBytes(StandardCharsets.UTF_8));
        chunk(upload, 0, "ab".getBytes(StandardCharsets.UTF_8));
        chunk(upload, 0, "ab".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/v2/uploads/{id}", upload)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UPLOADING"))
                .andExpect(jsonPath("$.data.uploaded_chunk_indexes[0]").value(0))
                .andExpect(jsonPath("$.data.uploaded_chunk_indexes[1]").value(2))
                .andExpect(jsonPath("$.data.uploaded_chunk_indexes.length()").value(2));
    }

    @Test
    void declaredHashIsCheckedAfterSmallLegacyChunksAreComposedAndCompletionIsIdempotent() throws Exception {
        String workspace = workspace();
        byte[] bytes = "完整的资料正文".getBytes(StandardCharsets.UTF_8);
        String upload = create(workspace, request(bytes.length, 2, hash(bytes))).path("upload_id").asText();
        chunk(upload, 0, java.util.Arrays.copyOfRange(bytes, 0, 5));
        chunk(upload, 1, java.util.Arrays.copyOfRange(bytes, 5, bytes.length));
        JsonNode first = complete(upload);
        assertThat(complete(upload)).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from source where workspace_id = ?", Integer.class, workspace)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select expected_sha256 from document_upload where id = ?", String.class, upload)).isEqualTo(hash(bytes));
        mvc.perform(get("/api/v2/uploads/{id}", upload)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void mismatchedWholeFileHashDoesNotCreateSourceOrTrustTheDeclaration() throws Exception {
        String workspace = workspace();
        byte[] bytes = "实际正文".getBytes(StandardCharsets.UTF_8);
        String upload = create(workspace, request(bytes.length, 1, "0".repeat(64))).path("upload_id").asText();
        chunk(upload, 0, bytes);
        mvc.perform(post("/api/v2/uploads/{id}/complete", upload)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UPLOAD_SHA256_MISMATCH"));
        assertThat(jdbc.queryForObject("select count(*) from source where workspace_id = ?", Integer.class, workspace)).isZero();
    }

    @Test
    void instantUploadUsesOnlyReadableSameWorkspaceSourceAndCreatesNoNewTask() throws Exception {
        String workspace = workspace();
        byte[] bytes = "可重复使用的资料".getBytes(StandardCharsets.UTF_8);
        String upload = create(workspace, request(bytes.length, 1, null)).path("upload_id").asText();
        chunk(upload, 0, bytes);
        JsonNode original = complete(upload);
        JsonNode instant = create(workspace, request(bytes.length, 1, hash(bytes)));
        assertThat(instant.path("instant").asBoolean()).isTrue();
        assertThat(instant.path("source_id").asText()).isEqualTo(original.path("source_id").asText());
        assertThat(instant.path("task_id").asText()).isEmpty();
        assertThat(complete(instant.path("upload_id").asText()).path("source_id")).isEqualTo(original.path("source_id"));
        assertThat(jdbc.queryForObject("select count(*) from source where workspace_id = ?", Integer.class, workspace)).isEqualTo(1);
        assertThat(create(workspace(), request(bytes.length, 1, hash(bytes))).path("instant").asBoolean()).isFalse();
        jdbc.update("update source set status = 'DELETED' where id = ?", original.path("source_id").asText());
        assertThat(create(workspace, request(bytes.length, 1, hash(bytes))).path("instant").asBoolean()).isFalse();
    }

    @Test
    void knownHashWithDifferentSizeIsRejectedAtCreation() throws Exception {
        String workspace = workspace();
        byte[] bytes = "大小一致".getBytes(StandardCharsets.UTF_8);
        String upload = create(workspace, request(bytes.length, 1, null)).path("upload_id").asText();
        chunk(upload, 0, bytes);
        complete(upload);
        mvc.perform(post("/api/v2/workspaces/{id}/uploads", workspace).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request(bytes.length + 1, 1, hash(bytes)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UPLOAD_FILE_SIZE_MISMATCH"));
    }

    @Test
    void instantUploadReturnsExistingActiveParseTaskAndCurrentSourceStatuses() throws Exception {
        String workspace = workspace();
        byte[] bytes = "处理中资料".getBytes(StandardCharsets.UTF_8);
        String upload = create(workspace, request(bytes.length, 1, null)).path("upload_id").asText();
        chunk(upload, 0, bytes);
        String source = complete(upload).path("source_id").asText();
        jdbc.update("update source set status = 'PROCESSING', parse_status = 'PARSING', index_status = 'PENDING' where id = ?", source);
        String task = tasks.createTask(workspace, "SOURCE_PARSE", "SOURCE", source, "PARSING", "已有解析任务");
        int before = jdbc.queryForObject("select count(*) from task where workspace_id = ?", Integer.class, workspace);
        JsonNode instant = create(workspace, request(bytes.length, 1, hash(bytes)));
        assertThat(instant.path("instant").asBoolean()).isTrue();
        assertThat(instant.path("task_id").asText()).isEqualTo(task);
        assertThat(instant.path("parse_status").asText()).isEqualTo("PARSING");
        assertThat(instant.path("index_status").asText()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from task where workspace_id = ?", Integer.class, workspace)).isEqualTo(before);
    }

    @Test
    void instantUploadRequiresTheStoredObjectToStillExist() throws Exception {
        String workspace = workspace();
        byte[] bytes = "原件必须存在".getBytes(StandardCharsets.UTF_8);
        String upload = create(workspace, request(bytes.length, 1, null)).path("upload_id").asText();
        chunk(upload, 0, bytes);
        complete(upload);
        String key = jdbc.queryForObject("select object_key from file_object where workspace_id = ? and sha256 = ?",
                String.class, workspace, hash(bytes));
        storage.delete("noteweave-source", key);
        assertThat(create(workspace, request(bytes.length, 1, hash(bytes))).path("instant").asBoolean()).isFalse();
    }

    @Test
    void missingExpiredAndStaleSessionsReturn404EvenWithoutChunkRows() throws Exception {
        mvc.perform(get("/api/v2/uploads/{id}", "missing-upload")).andExpect(status().isNotFound());
        String upload = create(workspace(), request(3, 1, null)).path("upload_id").asText();
        jdbc.update("update document_upload set updated_at = ? where id = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(90000)), upload);
        mvc.perform(get("/api/v2/uploads/{id}", upload)).andExpect(status().isNotFound());
        jdbc.update("update document_upload set status = 'EXPIRED', updated_at = current_timestamp where id = ?", upload);
        mvc.perform(get("/api/v2/uploads/{id}", upload)).andExpect(status().isNotFound());
    }

    @Test
    void formatsEndpointIncludesTheSameOfficeWhitelistAsMetadataValidation() throws Exception {
        mvc.perform(get("/api/v2/uploads/formats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.extension == 'docx')].mime_type").value(hasItem("application/vnd.openxmlformats-officedocument.wordprocessingml.document")));
    }
}
