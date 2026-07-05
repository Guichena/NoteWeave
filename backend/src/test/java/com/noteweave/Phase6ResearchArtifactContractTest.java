package com.noteweave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Phase6ResearchArtifactContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void artifactJobShouldCreateTaskExposeWorkerInputAndPersistVersion() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId, "artifact-input.md", """
                Artifact input source for report generation.
                It explains the current workspace material pool and output expectations.
                """);

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action_key", "report",
                                "style_profile_key", "interview",
                                "context_snapshot_id", "ctx-artifact-1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn();

        String artifactJobId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("artifact_job_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_type").value("ARTIFACT_JOB"))
                .andExpect(jsonPath("$.data.task_status").value("PENDING"));

        mockMvc.perform(get("/internal/worker/artifact-tasks/{taskId}/input", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspace_id").value(workspaceId))
                .andExpect(jsonPath("$.data.target_id").value(artifactJobId))
                .andExpect(jsonPath("$.data.input_payload.action_key").value("REPORT"))
                .andExpect(jsonPath("$.data.control_pack.pack_type").value("artifact"))
                .andExpect(jsonPath("$.data.source_scope.length()").value(1));

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/progress", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "COMPOSING",
                                "progress_percent", 45,
                                "message", "产物大纲已完成",
                                "metrics", Map.of("sections", 4)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "result_type", "MARKDOWN",
                                "result_title", "Alpha Report",
                                "result_payload", Map.of("markdown", "## Alpha Report\n\nArtifact content."),
                                "trace_summary", "artifact loop finished",
                                "citations", java.util.List.of(Map.of("title", "artifact-input.md"))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progress_phase").value("ARTIFACT_VERSIONED"));

        mockMvc.perform(get("/api/v2/tasks/{taskId}/events", taskId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: task.progress")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: task.completed")));

        Integer versionCount = jdbcTemplate.queryForObject(
                "select count(*) from artifact_version where artifact_job_id = ?",
                Integer.class,
                artifactJobId
        );
        assertThat(versionCount).isNotNull().isEqualTo(1);

        String markdown = jdbcTemplate.queryForObject(
                "select content_markdown from artifact_version where artifact_job_id = ? and version_no = 1",
                String.class,
                artifactJobId
        );
        assertThat(markdown).contains("Alpha Report");
    }

    @Test
    void researchRunShouldCreateTaskExposeWorkerInputAndPersistFinalReport() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId, "research-input.md", """
                Research input source for deep analysis.
                It contains a stable project context for the research worker.
                """);

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "How should AlphaResearch be summarized?",
                                "profile", "default",
                                "context_snapshot_id", "ctx-research-1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn();

        String researchRunId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("research_run_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        uploadSource(workspaceId, "late-research-input.md", """
                Late source uploaded after the research run was created.
                It must not enter the existing run source scope snapshot.
                """);

        mockMvc.perform(get("/internal/worker/research-tasks/{taskId}/input", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspace_id").value(workspaceId))
                .andExpect(jsonPath("$.data.target_id").value(researchRunId))
                .andExpect(jsonPath("$.data.input_payload.profile_key").value("DEFAULT"))
                .andExpect(jsonPath("$.data.control_pack.pack_type").value("research"))
                .andExpect(jsonPath("$.data.source_scope.length()").value(1))
                .andExpect(jsonPath("$.data.source_scope[0].title").value("research-input.md"))
                .andExpect(jsonPath("$.data.source_scope[0].sample_text")
                        .value(org.hamcrest.Matchers.containsString("Research input source")));

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/progress", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "VERIFYING",
                                "progress_percent", 70,
                                "message", "关键字段验证中",
                                "metrics", Map.of("verified_cells", 3)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "result_type", "RESEARCH_REPORT",
                                "result_title", "Alpha Research Report",
                                "result_payload", Map.of("report_markdown", "## Alpha Research Report\n\nVerified findings."),
                                "trace_summary", "research harness finished",
                                "citations", java.util.List.of(Map.of("title", "research-input.md"))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.result_ref").value(researchRunId));

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progress_phase").value("RESEARCH_REPORTED"));

        String reportMarkdown = jdbcTemplate.queryForObject(
                "select final_report_markdown from research_run where id = ?",
                String.class,
                researchRunId
        );
        assertThat(reportMarkdown).contains("Alpha Research Report");

        Integer traceCount = jdbcTemplate.queryForObject(
                "select count(*) from research_trace where research_run_id = ?",
                Integer.class,
                researchRunId
        );
        assertThat(traceCount).isNotNull().isGreaterThanOrEqualTo(2);
    }

    @Test
    void workerFailShouldMarkArtifactTaskAndJobAsFailed() throws Exception {
        String workspaceId = createWorkspace();

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action_key", "faq",
                                "style_profile_key", "brief",
                                "context_snapshot_id", "ctx-artifact-fail"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String artifactJobId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("artifact_job_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/fail", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "VERIFYING",
                                "error_code", "SCHEMA_INVALID",
                                "error_message", "schema gate rejected current artifact output",
                                "retryable", false
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("FAILED"))
                .andExpect(jsonPath("$.data.progress_phase").value("VERIFYING"));

        String artifactStatus = jdbcTemplate.queryForObject(
                "select status from artifact_job where id = ?",
                String.class,
                artifactJobId
        );
        assertThat(artifactStatus).isEqualTo("FAILED");
    }

    @Test
    void workerFailShouldMarkResearchTaskAndRunAsFailed() throws Exception {
        String workspaceId = createWorkspace();

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "Which research direction should fail safely?",
                                "profile", "default",
                                "context_snapshot_id", "ctx-research-fail"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String researchRunId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("research_run_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/fail", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "VERIFYING",
                                "error_code", "GLOBAL_VERIFIER_REJECTED",
                                "error_message", "global verifier rejected current research report",
                                "retryable", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("FAILED"))
                .andExpect(jsonPath("$.data.progress_phase").value("VERIFYING"));

        String researchStatus = jdbcTemplate.queryForObject(
                "select status from research_run where id = ?",
                String.class,
                researchRunId
        );
        assertThat(researchStatus).isEqualTo("FAILED");

        Integer failTraceCount = jdbcTemplate.queryForObject(
                "select count(*) from research_trace where research_run_id = ? and trace_type = 'FAILED'",
                Integer.class,
                researchRunId
        );
        assertThat(failTraceCount).isNotNull().isEqualTo(1);
    }

    private String createWorkspace() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Agent 工作台",
                                "description", "用于 Research / Artifact 契约测试"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("workspace_id").asText();
    }

    private void uploadSource(String workspaceId, String fileName, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        MvcResult init = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/uploads", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "file_name", fileName,
                                "file_size", bytes.length,
                                "mime_type", "text/markdown",
                                "chunk_size", bytes.length,
                                "total_chunks", 1
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String uploadId = objectMapper.readTree(init.getResponse().getContentAsString()).path("data").path("upload_id").asText();
        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, 0)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(bytes))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v2/uploads/{uploadId}/complete", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parse_status").value("PARSED"))
                .andExpect(jsonPath("$.data.index_status").value("INDEXED"));
    }
}
