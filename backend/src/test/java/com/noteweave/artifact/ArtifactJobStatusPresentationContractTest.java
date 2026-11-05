package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 验收 11-04 中危 7、10：产物失败原因面向用户，排队中给出排队位置。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ArtifactJobStatusPresentationContractTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void queuedJobsReportTheirPositionInOrder() throws Exception {
        String workspaceId = createWorkspace();
        String sourceId = uploadSource(workspaceId);
        String first = createJob(workspaceId, sourceId);
        String second = createJob(workspaceId, sourceId);
        // 入队时间拉开，避免同一毫秒内创建导致顺序只靠编号决定
        jdbcTemplate.update("update artifact_job set status = 'QUEUED', updated_at = timestamp '2026-01-01 00:00:00' where id = ?", first);
        jdbcTemplate.update("update artifact_job set status = 'QUEUED', updated_at = timestamp '2026-01-01 00:00:01' where id = ?", second);

        int firstPosition = getJob(workspaceId, first).path("queue_position").asInt();
        int secondPosition = getJob(workspaceId, second).path("queue_position").asInt();
        assertThat(firstPosition).isPositive();
        assertThat(secondPosition).isEqualTo(firstPosition + 1);

        JsonNode list = getJson("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId);
        assertThat(list.findValues("queue_position")).isNotEmpty();

        // 开始执行后不再返回排队位置
        jdbcTemplate.update("update artifact_job set status = 'RUNNING' where id = ?", first);
        assertThat(getJob(workspaceId, first).has("queue_position")).isFalse();
        assertThat(getJob(workspaceId, second).path("queue_position").asInt()).isEqualTo(firstPosition);
    }

    @Test
    void failedJobShowsFriendlyReasonAndKeepsOriginalAsDetail() throws Exception {
        String workspaceId = createWorkspace();
        String sourceId = uploadSource(workspaceId);
        String jobId = createJob(workspaceId, sourceId);
        String raw = "产物骨架数量不符合契约或用户选项";
        jdbcTemplate.update("update artifact_job set status = 'FAILED' where id = ?", jobId);
        jdbcTemplate.update("""
                update task set task_status = 'FAILED', progress_message = ?
                where id = (select task_id from artifact_job where id = ?)
                """, raw, jobId);

        JsonNode job = getJob(workspaceId, jobId);
        assertThat(job.path("progress_message").asText()).isEqualTo(ArtifactFailureMessages.FORMAT_CHECK);
        assertThat(job.path("failure_detail").asText()).isEqualTo(raw);
        assertThat(job.has("queue_position")).isFalse();

        JsonNode list = getJson("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId);
        assertThat(list.get(0).path("progress_message").asText()).doesNotContain("契约", "骨架");
    }

    private JsonNode getJob(String workspaceId, String jobId) throws Exception {
        return getJson("/api/v2/workspaces/{workspaceId}/artifact-jobs/" + jobId, workspaceId);
    }

    private JsonNode getJson(String path, String workspaceId) throws Exception {
        MvcResult result = mockMvc.perform(get(path, workspaceId)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private String createJob(String workspaceId, String sourceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "briefing",
                                "user_requirement", "排队位置测试",
                                "inputs", Map.of("length", "STANDARD"),
                                "source_scope_source_ids", List.of(sourceId)))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("artifact_job_id").asText();
    }

    private String createWorkspace() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "产物状态展示工作台"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("workspace_id").asText();
    }

    private String uploadSource(String workspaceId) throws Exception {
        byte[] bytes = "产物排队与失败展示测试用资料。".getBytes(StandardCharsets.UTF_8);
        MvcResult init = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/uploads", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "file_name", "artifact-status.md",
                                "file_size", bytes.length,
                                "mime_type", "text/markdown",
                                "chunk_size", bytes.length,
                                "total_chunks", 1))))
                .andExpect(status().isOk())
                .andReturn();
        String uploadId = objectMapper.readTree(init.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("upload_id").asText();
        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, 0)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(bytes))
                .andExpect(status().isOk());
        MvcResult complete = mockMvc.perform(post("/api/v2/uploads/{uploadId}/complete", uploadId))
                .andExpect(status().isOk())
                .andReturn();
        String sourceId = objectMapper.readTree(complete.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("source_id").asText();
        jdbcTemplate.update("update source set status = 'READY', index_status = 'INDEXED' where id = ?", sourceId);
        jdbcTemplate.update("update source_snapshot set index_status = 'INDEXED' where source_id = ?", sourceId);
        jdbcTemplate.update("update source_chunk set projection_status = 'PROJECTED', projected_at = current_timestamp where source_id = ?", sourceId);
        return sourceId;
    }
}
