package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.worker.WorkerCompleteRequest;
import com.noteweave.worker.WorkerTaskCallbackService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
class ArtifactPipelineFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ArtifactJobService jobs;
    @Autowired WorkerTaskCallbackService callbacks;
    @MockBean ArtifactWorkerExportClient exports;

    @Test
    void newPipelinePublishesAReadableVersionAndRegeneratesAnOldAlias() throws Exception {
        String workspace = workspace();
        String source = upload(workspace);
        JsonNode created = create(workspace, source, "report_draft");
        assertThat(created.path("skill_key").asText()).isEqualTo("briefing");
        complete(created.path("task_id").asText(), "pipeline-result-v1.json");
        String job = created.path("artifact_job_id").asText();
        mvc.perform(get("/api/v2/workspaces/{w}/artifact-jobs/{j}/versions/1", workspace, job))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.kind").value("DOC"))
                .andExpect(jsonPath("$.data.content_markdown").value(org.hamcrest.Matchers.containsString("缓存有效期为30秒")))
                .andExpect(jsonPath("$.data.files[0].status").value("READY"));
        jdbc.update("update artifact_job set skill_key = 'report_draft', inputs_json = ? where id = ?",
                "{\"language\":\"zh-CN\"}", job);
        JsonNode regenerated = data(mvc.perform(post("/api/v2/workspaces/{w}/artifact-jobs/{j}/versions/1/regenerate", workspace, job)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.skill_key").value("report_draft"))
                .andReturn().getResponse().getContentAsString());
        String regeneratedTask = regenerated.path("task_id").asText();
        assertThat(jobs.getWorkerInput(regeneratedTask).inputPayload().inputs()).isEqualTo(Map.of("length", "STANDARD"));
        complete(regeneratedTask, "pipeline-result-v1.json");
        mvc.perform(get("/api/v2/workspaces/{w}/artifact-jobs/{j}/versions/2", workspace, job))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.kind").value("DOC"));
    }

    @Test
    void tableCsvUsesTheExistingPublicationAndDownloadChain() throws Exception {
        String workspace = workspace();
        String source = upload(workspace);
        byte[] csv = java.util.Base64.getMimeDecoder().decode(Files.readString(Path.of(
                "../workers/artifact-worker/tests/pipeline/pipeline-table-v1.csv.base64")));
        when(exports.fetch(anyString(), eq("content.csv"))).thenReturn(csv);
        JsonNode created = create(workspace, source, "data-table");
        complete(created.path("task_id").asText(), "pipeline-table-v1.json");
        String job = created.path("artifact_job_id").asText();
        JsonNode detail = data(mvc.perform(get("/api/v2/workspaces/{w}/artifact-jobs/{j}/versions/1", workspace, job))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String fileId = "";
        for (JsonNode file : detail.path("files")) {
            if (file.path("file_format").asText().equals("CSV")) fileId = file.path("file_id").asText();
        }
        assertThat(fileId).isNotBlank();
        mvc.perform(get("/api/v2/workspaces/{w}/artifact-jobs/{j}/versions/1/files/{f}", workspace, job, fileId))
                .andExpect(status().isOk()).andExpect(content().bytes(csv));
    }

    @SuppressWarnings("unchecked")
    private void complete(String task, String fixture) throws Exception {
        // 真实 Python 流水线生成的跨语言样本，仅替换本轮宿主分配的任务和资料标识。
        Map<String, Object> payload = mapper.readValue(Files.readAllBytes(Path.of(
                "../workers/artifact-worker/tests/pipeline/" + fixture)), Map.class);
        ArtifactWorkerInputResponse input = jobs.getWorkerInput(task);
        var source = input.sourceScope().get(0);
        Map<String, Object> pack = (Map<String, Object>) payload.get("evidence");
        for (Map<String, Object> sentence : (List<Map<String, Object>>) pack.get("sentences")) {
            sentence.put("source_id", source.sourceId());
            sentence.put("source_snapshot_id", source.sourceSnapshotId());
        }
        Map<String, Object> candidate = (Map<String, Object>) payload.get("candidate");
        candidate.put("task_id", task);
        candidate.put("input_snapshot_id", input.inputSnapshotId());
        candidate.put("catalog_digest", input.catalogDigest());
        candidate.put("candidate_id", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((task + ":" + input.inputSnapshotId() + ":" + candidate.get("content_sha256")).getBytes(StandardCharsets.UTF_8))));
        var result = callbacks.complete(task, new WorkerCompleteRequest("MARKDOWN", "缓存学习材料", payload, "五段流水线测试", List.of()), "pipeline:" + task);
        assertThat(result.status()).isEqualTo("COMPLETED");
    }

    @Test
    void deckAndPdfUseTheExistingVersionPublicationAndDownloadChain() throws Exception {
        for (String skill : List.of("video-deck", "lecture-notes-pdf")) {
            String fixture = skill.equals("video-deck") ? "pipeline-deck-v1" : "pipeline-pdf-v1";
            byte[] archive = java.util.Base64.getMimeDecoder().decode(Files.readString(Path.of(
                    "../workers/artifact-worker/tests/pipeline/" + fixture + ".files.zip.base64")));
            Map<String, byte[]> expected = new java.util.LinkedHashMap<>();
            try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    byte[] bytes = zip.readAllBytes();
                    expected.put(entry.getName(), bytes);
                    when(exports.fetch(anyString(), eq(entry.getName()))).thenReturn(bytes);
                }
            }
            String workspace = workspace();
            JsonNode created = create(workspace, upload(workspace), skill);
            complete(created.path("task_id").asText(), fixture + ".json");
            String job = created.path("artifact_job_id").asText();
            JsonNode detail = data(mvc.perform(get("/api/v2/workspaces/{w}/artifact-jobs/{j}/versions/1", workspace, job))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(detail.path("kind").asText()).isEqualTo(skill.equals("video-deck") ? "DECK" : "DOC");
            assertThat(detail.path("ir").path("kind").asText()).isEqualTo(detail.path("kind").asText());
            int downloaded = 0;
            for (JsonNode file : detail.path("files")) {
                if (file.path("file_format").asText().equals("MARKDOWN")) continue;
                String name = file.path("file_name").asText();
                assertThat(expected).containsKey(name);
                mvc.perform(get("/api/v2/workspaces/{w}/artifact-jobs/{j}/versions/1/files/{f}",
                            workspace, job, file.path("file_id").asText()))
                        .andExpect(status().isOk()).andExpect(content().bytes(expected.get(name)));
                downloaded++;
            }
            assertThat(downloaded).isEqualTo(expected.size());
        }
    }

    private JsonNode create(String workspace, String source, String skill) throws Exception {
        return data(mvc.perform(post("/api/v2/workspaces/{w}/artifact-jobs", workspace)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                    "skill_key", skill, "user_requirement", "整理缓存资料", "inputs", Map.of(), "source_scope_source_ids", List.of(source)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String workspace() throws Exception {
        return data(mvc.perform(post("/api/v2/workspaces").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"产物流水线测试\",\"description\":\"版本交付验证\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("workspace_id").asText();
    }

    private String upload(String workspace) throws Exception {
        byte[] bytes = "缓存有效期为30秒。过期后重新读取数据。".getBytes(StandardCharsets.UTF_8);
        String upload = data(mvc.perform(post("/api/v2/workspaces/{w}/uploads", workspace)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                    "file_name", "缓存资料.md", "file_size", bytes.length, "mime_type", "text/markdown", "chunk_size", bytes.length, "total_chunks", 1))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("upload_id").asText();
        mvc.perform(put("/api/v2/uploads/{u}/chunks/0", upload).contentType(MediaType.APPLICATION_OCTET_STREAM).content(bytes))
                .andExpect(status().isOk());
        String source = data(mvc.perform(post("/api/v2/uploads/{u}/complete", upload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("source_id").asText();
        jdbc.update("update source set status = 'READY', index_status = 'INDEXED' where id = ?", source);
        jdbc.update("update source_snapshot set index_status = 'INDEXED' where source_id = ?", source);
        return source;
    }

    private JsonNode data(String value) throws Exception { return mapper.readTree(value).path("data"); }
}
