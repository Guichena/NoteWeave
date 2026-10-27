package com.noteweave.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.source.SourceParseService;
import com.noteweave.storage.ObjectStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadRollbackCleanupContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UploadService uploads;
    @Autowired ObjectStorage storage;
    @MockBean SourceParseService parser;

    private String workspace() throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v2/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "上传回滚验证"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("workspace_id").asText();
    }

    private String prepare(String workspace, byte[] bytes) {
        String upload = uploads.createUpload(workspace, new CreateUploadRequest("资料.txt", bytes.length, "text/plain", bytes.length, 1)).uploadId();
        uploads.acceptChunk(upload, 0, null, bytes);
        return upload;
    }

    private void failParsing() {
        doThrow(new IllegalStateException("同步解析失败"))
                .when(parser).parseAndIndex(anyString(), anyString(), anyString(), any(Path.class));
    }

    @Test
    void parseFailureRollsBackDatabaseAndDeletesOnlyNewPermanentObjects() throws Exception {
        String workspace = workspace();
        byte[] bytes = "失败后可重试的正文".getBytes(StandardCharsets.UTF_8);
        String upload = prepare(workspace, bytes);
        failParsing();
        assertThatThrownBy(() -> uploads.completeUpload(upload)).isInstanceOf(IllegalStateException.class).hasMessage("同步解析失败");
        assertThat(jdbc.queryForObject("select count(*) from file_object where workspace_id = ?", Integer.class, workspace)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from source where workspace_id = ?", Integer.class, workspace)).isZero();
        assertThat(storage.list("noteweave-source", "workspace/" + workspace + "/file_object/", "", 100)).isEmpty();
        assertThat(storage.list("noteweave-source", "workspace/" + workspace + "/source/", "", 100)).isEmpty();
        assertThat(uploads.getUpload(upload).status()).isEqualTo("UPLOADING");
        assertThat(uploads.getUpload(upload).uploadedChunkIndexes()).containsExactly(0);
        assertThat(storage.exists("noteweave-source", jdbc.queryForObject(
                "select object_key from upload_chunk where upload_id = ? and chunk_index = 0",
                String.class, upload))).isTrue();
    }

    /**
     * UP-02：同一分片重传不同内容时，行与对象必须仍然一致。
     * 此前分片键只由 (uploadId, 序号) 决定，重传会覆盖同一个对象，
     * 行里的 content_md5 与对象实际内容可能对不上，合并时就会串到上一次的分片。
     */
    @Test
    void reuploadingDifferentChunkContentKeepsRowAndObjectInStep() throws Exception {
        String workspace = workspace();
        byte[] first = "第一批分片内容".getBytes(StandardCharsets.UTF_8);
        byte[] second = "第二批完全不同的分片内容".getBytes(StandardCharsets.UTF_8);
        int declaredSize = Math.max(first.length, second.length);
        String upload = uploads.createUpload(workspace,
                new CreateUploadRequest("资料.txt", declaredSize, "text/plain", declaredSize, 1)).uploadId();

        uploads.acceptChunk(upload, 0, null, first);
        String firstKey = jdbc.queryForObject(
                "select object_key from upload_chunk where upload_id = ? and chunk_index = 0", String.class, upload);
        uploads.acceptChunk(upload, 0, null, second);
        String secondKey = jdbc.queryForObject(
                "select object_key from upload_chunk where upload_id = ? and chunk_index = 0", String.class, upload);

        assertThat(secondKey).isNotEqualTo(firstKey);
        assertThat(new String(storage.read("noteweave-source", secondKey), StandardCharsets.UTF_8))
                .isEqualTo("第二批完全不同的分片内容");
        // 上一次的分片对象不被覆盖：重传不会把旧内容写进新键，也不会让新键指向旧内容
        assertThat(new String(storage.read("noteweave-source", firstKey), StandardCharsets.UTF_8))
                .isEqualTo("第一批分片内容");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parseFailureWithReusedFileObjectPreservesSharedObjectAndOriginalSnapshot(boolean sharedOriginalMissing) throws Exception {
        String workspace = workspace();
        byte[] bytes = "已被其他资料使用的正文".getBytes(StandardCharsets.UTF_8);
        doAnswer(call -> {
            String parseWorkspace = call.getArgument(0, String.class);
            String parseSource = call.getArgument(1, String.class);
            jdbc.update("update source set status = 'READY', parse_status = 'PARSED', index_status = 'DISABLED' where workspace_id = ? and id = ?",
                    new Object[]{parseWorkspace, parseSource});
            return null;
        }).when(parser).parseAndIndex(anyString(), anyString(), anyString(), any(Path.class));
        CompleteUploadResponse original = uploads.completeUpload(prepare(workspace, bytes));
        String sharedKey = jdbc.queryForObject("select object_key from file_object where workspace_id = ?", String.class, workspace);
        String snapshotKey = jdbc.queryForObject("select object_key from source_snapshot where source_id = ?", String.class, original.sourceId());
        if (sharedOriginalMissing) storage.delete("noteweave-source", sharedKey);
        String upload = prepare(workspace, bytes);
        failParsing();
        assertThatThrownBy(() -> uploads.completeUpload(upload)).isInstanceOf(IllegalStateException.class).hasMessage("同步解析失败");
        assertThat(jdbc.queryForObject("select count(*) from file_object where workspace_id = ?", Integer.class, workspace)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select ref_count from file_object where workspace_id = ?", Integer.class, workspace)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from source where workspace_id = ?", Integer.class, workspace)).isEqualTo(1);
        assertThat(storage.exists("noteweave-source", sharedKey)).isTrue();
        assertThat(storage.read("noteweave-source", sharedKey)).containsExactly(bytes);
        assertThat(storage.list("noteweave-source", "workspace/" + workspace + "/file_object/", "", 100))
                .extracting(ObjectStorage.StoredObject::key).containsExactly(sharedKey);
        assertThat(storage.list("noteweave-source", "workspace/" + workspace + "/source/", "", 100))
                .extracting(ObjectStorage.StoredObject::key).containsExactly(snapshotKey);
    }
}
