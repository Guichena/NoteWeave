package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.service.LlmClient;
import com.noteweave.support.ContainerizedIntegrationTest;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.service.TaskDispatcher;
import com.noteweave.team.document.dto.DocumentProcessTaskPayload;
import com.noteweave.team.document.service.DocumentProcessingService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Phase8StudioArtifactIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskDispatcher taskDispatcher;

    @Autowired
    private DocumentProcessingService documentProcessingService;

    @MockBean
    private LlmClient llmClient;

    @Test
    void studioTaskShouldGenerateProjectArtifactExportAndRedactedSkillLogs() throws Exception {
        String ownerToken = registerAndGetToken("phase8_owner_" + System.nanoTime());
        String outsiderToken = registerAndGetToken("phase8_outsider_" + System.nanoTime());

        JsonNode project = createProject(ownerToken, "Phase 8 project", "artifact generation", "report");
        Long projectId = project.path("data").path("id").asLong();
        Long spaceId = project.path("data").path("spaceId").asLong();

        JsonNode source = addTextSource(
                ownerToken,
                projectId,
                "RAG project notes",
                """
                RAG combines retrieval and generation.
                A vector store keeps embeddings for semantic search.
                """
        );
        Long sourceId = source.path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(articleJson(sourceId)))
                .willReturn(llmResponse(conceptJson(sourceId)))
                .willReturn(llmResponse("""
                        # RAG Technical Report

                        ## Executive Summary
                        Retrieval-augmented generation combines retrieval and generation to keep answers grounded.

                        ## Evidence
                        - Vector stores support semantic search.
                        """));

        Long compileTaskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(compileTaskId, TaskStatus.SUCCESS);

        JsonNode studioTask = createStudioTask(ownerToken, """
                {
                  "spaceId": %d,
                  "researchProjectId": %d,
                  "taskType": "ARTIFACT_GENERATE",
                  "sourceScopeType": "RESEARCH_PROJECT",
                  "sourceIds": [%d],
                  "params": {
                    "artifactType": "REPORT",
                    "topic": "RAG Technical Report",
                    "length": "MEDIUM",
                    "includeCitations": true
                  }
                }
                """.formatted(spaceId, projectId, projectId));

        Long artifactId = studioTask.path("data").path("artifactId").asLong();
        Long taskId = studioTask.path("data").path("taskId").asLong();

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.SUCCESS);

        mockMvc.perform(get("/api/v1/artifacts/{artifactId}", artifactId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(artifactId))
                .andExpect(jsonPath("$.data.artifactType").value("REPORT"))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.latestVersionNo").value(1))
                .andExpect(jsonPath("$.data.citations[0].sourceType").value("SOURCE"))
                .andExpect(jsonPath("$.data.sources[0].sourceType").exists());

        mockMvc.perform(get("/api/v1/tasks/{taskId}/skill-logs", taskId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].skillName").value("LoadGenerationContextSkill"))
                .andExpect(jsonPath("$.data[1].skillName").value("SelectEvidenceSkill"))
                .andExpect(jsonPath("$.data[2].skillName").value("GenerateReportSkill"))
                .andExpect(jsonPath("$.data[2].input.redacted").value(true))
                .andExpect(jsonPath("$.data[2].output.redacted").value(true))
                .andExpect(jsonPath("$.data[3].skillName").value("SaveArtifactSkill"));

        mockMvc.perform(get("/api/v1/artifacts/{artifactId}/export", artifactId)
                        .queryParam("format", "markdown")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.format").value("markdown"))
                .andExpect(jsonPath("$.data.objectKey").value(org.hamcrest.Matchers.containsString("/artifacts/" + artifactId + "/exports/")))
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("RAG Technical Report")));

        mockMvc.perform(get("/api/v1/artifacts/{artifactId}", artifactId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ARTIFACT_ACCESS_DENIED"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from artifact_version where artifact_id = ?",
                Integer.class,
                artifactId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from artifact_source where artifact_id = ?",
                Integer.class,
                artifactId
        )).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from artifact_citation where artifact_id = ?",
                Integer.class,
                artifactId
        )).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from skill_execution_log where task_id = ?",
                Integer.class,
                taskId
        )).isEqualTo(4);

        String generateLogInput = jdbcTemplate.queryForObject(
                "select input_json from skill_execution_log where task_id = ? and skill_name = 'GenerateReportSkill'",
                String.class,
                taskId
        );
        assertThat(generateLogInput)
                .contains("\"redacted\":true")
                .doesNotContain("RAG combines retrieval and generation.");
    }

    @Test
    void artifactUpdateAndRegenerateShouldAppendVersionsWithoutOverwritingHistory() throws Exception {
        String ownerToken = registerAndGetToken("phase8_editor_" + System.nanoTime());
        JsonNode project = createProject(ownerToken, "Version project", null, null);
        Long projectId = project.path("data").path("id").asLong();
        Long spaceId = project.path("data").path("spaceId").asLong();

        JsonNode source = addTextSource(
                ownerToken,
                projectId,
                "Version source",
                """
                Comparison reports should keep a full history.
                Regeneration must not overwrite manual edits.
                """
        );
        Long sourceId = source.path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(articleJson(sourceId)))
                .willReturn(llmResponse(conceptJson(sourceId)))
                .willReturn(llmResponse("""
                        # Comparison Memo

                        Initial generated artifact content.
                        """))
                .willReturn(llmResponse("""
                        # Comparison Memo Regenerated

                        Regenerated artifact content after manual edits.
                        """));

        Long compileTaskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(compileTaskId, TaskStatus.SUCCESS);

        JsonNode studioTask = createStudioTask(ownerToken, """
                {
                  "spaceId": %d,
                  "researchProjectId": %d,
                  "taskType": "ARTIFACT_GENERATE",
                  "sourceScopeType": "RESEARCH_PROJECT",
                  "sourceIds": [%d],
                  "params": {
                    "artifactType": "COMPARISON",
                    "topic": "Comparison Memo",
                    "includeCitations": true
                  }
                }
                """.formatted(spaceId, projectId, projectId));

        Long artifactId = studioTask.path("data").path("artifactId").asLong();
        Long firstTaskId = studioTask.path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(firstTaskId, TaskStatus.SUCCESS);

        mockMvc.perform(put("/api/v1/artifacts/{artifactId}", artifactId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Comparison Memo Edited",
                                  "content": "# Comparison Memo Edited\\n\\nManual revision that must stay in history.",
                                  "changeNote": "manual refinement"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestVersionNo").value(2))
                .andExpect(jsonPath("$.data.title").value("Comparison Memo Edited"));

        String versionOneContent = jdbcTemplate.queryForObject(
                "select content from artifact_version where artifact_id = ? and version_no = 1",
                String.class,
                artifactId
        );
        String versionTwoContent = jdbcTemplate.queryForObject(
                "select content from artifact_version where artifact_id = ? and version_no = 2",
                String.class,
                artifactId
        );

        assertThat(versionOneContent).contains("Initial generated artifact content.");
        assertThat(versionTwoContent).contains("Manual revision that must stay in history.");

        JsonNode regenerate = objectMapper.readTree(mockMvc.perform(post("/api/v1/artifacts/{artifactId}/regenerate", artifactId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "params": {
                                    "topic": "Comparison Memo Regenerated"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        Long regenerateTaskId = regenerate.path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(regenerateTaskId, TaskStatus.SUCCESS);

        mockMvc.perform(get("/api/v1/artifacts/{artifactId}", artifactId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestVersionNo").value(3))
                .andExpect(jsonPath("$.data.title").value("Comparison Memo Regenerated"));

        String versionThreeContent = jdbcTemplate.queryForObject(
                "select content from artifact_version where artifact_id = ? and version_no = 3",
                String.class,
                artifactId
        );
        assertThat(versionThreeContent).contains("Regenerated artifact content after manual edits.");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from artifact_version where artifact_id = ?",
                Integer.class,
                artifactId
        )).isEqualTo(3);
    }

    @Test
    void viewerShouldGenerateFaqFromChatSessionAndArchiveShouldSoftDeleteArtifact() throws Exception {
        String ownerToken = registerAndGetToken("phase8_team_owner_" + System.nanoTime());
        String viewerName = "phase8_team_viewer_" + System.nanoTime();
        String viewerToken = registerAndGetToken(viewerName);
        String outsiderToken = registerAndGetToken("phase8_team_outsider_" + System.nanoTime());

        Long spaceId = createTeamSpace(ownerToken, "phase8-team-" + System.nanoTime());
        addMember(ownerToken, spaceId, viewerName + "@example.com", "VIEWER");
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase8-kb-" + System.nanoTime());
        IndexedDocument indexedDocument = uploadAndProcess(
                ownerToken,
                spaceId,
                kbId,
                "deploy.txt",
                "text/plain",
                "Rollback should be rehearsed before production release.".getBytes(StandardCharsets.UTF_8)
        );

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("Rollback should be rehearsed before production release."))
                .willReturn(llmResponse("""
                        # Deployment FAQ

                        ## What must happen before release?
                        Rollback should be rehearsed before production release.
                        """));

        Long sessionId = createChatSession(viewerToken, spaceId, "deployment faq", "KNOWLEDGE_BASE", new long[]{kbId});
        Long assistantMessageId = askQuestion(viewerToken, sessionId, "What must happen before release?");

        mockMvc.perform(post("/api/v1/studio/tasks")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "spaceId": %d,
                                  "taskType": "ARTIFACT_GENERATE",
                                  "sourceScopeType": "CHAT_MESSAGE",
                                  "sourceIds": [%d],
                                  "createdFromSessionId": %d,
                                  "createdFromMessageId": %d,
                                  "params": {
                                    "artifactType": "FAQ",
                                    "topic": "Illegal FAQ"
                                  }
                                }
                                """.formatted(spaceId, assistantMessageId, sessionId, assistantMessageId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SPACE_ACCESS_DENIED"));

        JsonNode studioTask = createStudioTask(viewerToken, """
                {
                  "spaceId": %d,
                  "taskType": "ARTIFACT_GENERATE",
                  "sourceScopeType": "CHAT_MESSAGE",
                  "sourceIds": [%d],
                  "createdFromSessionId": %d,
                  "createdFromMessageId": %d,
                  "params": {
                    "artifactType": "FAQ",
                    "topic": "Deployment FAQ"
                  }
                }
                """.formatted(spaceId, assistantMessageId, sessionId, assistantMessageId));

        Long artifactId = studioTask.path("data").path("artifactId").asLong();
        Long taskId = studioTask.path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.SUCCESS);

        mockMvc.perform(get("/api/v1/chat/sessions/{sessionId}/artifacts", sessionId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(artifactId))
                .andExpect(jsonPath("$.data[0].artifactType").value("FAQ"));

        mockMvc.perform(get("/api/v1/artifacts/{artifactId}", artifactId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdFromSessionId").value(sessionId))
                .andExpect(jsonPath("$.data.createdFromMessageId").value(assistantMessageId))
                .andExpect(jsonPath("$.data.citations[0].sourceType").value("DOCUMENT"))
                .andExpect(jsonPath("$.data.citations[0].sourceId").value(indexedDocument.documentId()))
                .andExpect(jsonPath("$.data.sources[0].sourceType").value("CHAT_MESSAGE"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from session_artifact where session_id = ? and artifact_id = ?",
                Integer.class,
                sessionId,
                artifactId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from artifact where id = ?",
                String.class,
                artifactId
        )).isEqualTo("READY");

        mockMvc.perform(delete("/api/v1/artifacts/{artifactId}", artifactId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "select status from artifact where id = ?",
                String.class,
                artifactId
        )).isEqualTo("ARCHIVED");
        assertThat(jdbcTemplate.queryForObject(
                "select case when deleted_at is null then 0 else 1 end from artifact where id = ?",
                Integer.class,
                artifactId
        )).isEqualTo(1);
    }

    @Test
    void cancelPendingArtifactTaskShouldReconcilePlaceholderToFailed() throws Exception {
        String ownerToken = registerAndGetToken("phase8_cancel_pending_" + System.nanoTime());
        JsonNode project = createProject(ownerToken, "Cancel pending project", null, null);
        Long projectId = project.path("data").path("id").asLong();
        Long spaceId = project.path("data").path("spaceId").asLong();

        JsonNode source = addTextSource(
                ownerToken,
                projectId,
                "Pending cancel source",
                """
                RAG combines retrieval and generation.
                A vector store keeps embeddings for semantic search.
                """
        );
        Long sourceId = source.path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(articleJson(sourceId)))
                .willReturn(llmResponse(conceptJson(sourceId)));

        Long compileTaskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(compileTaskId, TaskStatus.SUCCESS);

        JsonNode studioTask = createStudioTask(ownerToken, """
                {
                  "spaceId": %d,
                  "researchProjectId": %d,
                  "taskType": "ARTIFACT_GENERATE",
                  "sourceScopeType": "RESEARCH_PROJECT",
                  "sourceIds": [%d],
                  "params": {
                    "artifactType": "REPORT",
                    "topic": "Pending cancel artifact"
                  }
                }
                """.formatted(spaceId, projectId, projectId));

        Long artifactId = studioTask.path("data").path("artifactId").asLong();
        Long taskId = studioTask.path("data").path("taskId").asLong();

        mockMvc.perform(post("/api/v1/studio/tasks/{taskId}/cancel", taskId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "select task_status from task where id = ?",
                String.class,
                taskId
        )).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from artifact where id = ?",
                String.class,
                artifactId
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from artifact_version where artifact_id = ?",
                Integer.class,
                artifactId
        )).isEqualTo(0);
    }

    @Test
    void artifactListShouldSupportQueryFiltersAndKeywordSearch() throws Exception {
        String ownerToken = registerAndGetToken("phase8_query_" + System.nanoTime());
        JsonNode project = createProject(ownerToken, "Artifact query project", null, null);
        Long projectId = project.path("data").path("id").asLong();
        Long spaceId = project.path("data").path("spaceId").asLong();

        JsonNode source = addTextSource(
                ownerToken,
                projectId,
                "Artifact query source",
                """
                RAG combines retrieval and generation.
                A vector store keeps embeddings for semantic search.
                """
        );
        Long sourceId = source.path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(articleJson(sourceId)))
                .willReturn(llmResponse(conceptJson(sourceId)))
                .willReturn(llmResponse("""
                        # Deployment FAQ

                        Rollback rehearsal should happen before release.
                        """))
                .willReturn(llmResponse("""
                        # Comparison Notes

                        This comparison focuses on deployment trade-offs.
                        """));

        Long compileTaskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(compileTaskId, TaskStatus.SUCCESS);

        JsonNode faqTask = createStudioTask(ownerToken, """
                {
                  "spaceId": %d,
                  "researchProjectId": %d,
                  "taskType": "ARTIFACT_GENERATE",
                  "sourceScopeType": "RESEARCH_PROJECT",
                  "sourceIds": [%d],
                  "params": {
                    "artifactType": "FAQ",
                    "topic": "Deployment FAQ"
                  }
                }
                """.formatted(spaceId, projectId, projectId));
        Long faqArtifactId = faqTask.path("data").path("artifactId").asLong();
        Long faqTaskId = faqTask.path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(faqTaskId, TaskStatus.SUCCESS);
        jdbcTemplate.update(
                "update artifact set updated_at = DATE_SUB(updated_at, interval 1 second) where id = ?",
                faqArtifactId
        );

        JsonNode comparisonTask = createStudioTask(ownerToken, """
                {
                  "spaceId": %d,
                  "researchProjectId": %d,
                  "taskType": "ARTIFACT_GENERATE",
                  "sourceScopeType": "RESEARCH_PROJECT",
                  "sourceIds": [%d],
                  "params": {
                    "artifactType": "COMPARISON",
                    "topic": "Comparison Notes"
                  }
                }
                """.formatted(spaceId, projectId, projectId));
        Long comparisonArtifactId = comparisonTask.path("data").path("artifactId").asLong();
        Long comparisonTaskId = comparisonTask.path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(comparisonTaskId, TaskStatus.SUCCESS);

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/artifacts", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .queryParam("artifactType", "FAQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(faqArtifactId))
                .andExpect(jsonPath("$.data[0].artifactType").value("FAQ"));

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/artifacts", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .queryParam("keyword", "trade-offs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(comparisonArtifactId));

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/artifacts", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .queryParam("status", "READY")
                        .queryParam("researchProjectId", String.valueOf(projectId))
                        .queryParam("sourceScopeType", "RESEARCH_PROJECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        List<Long> artifactIds = jdbcTemplate.queryForList(
                "select id from artifact where id in (?, ?) order by updated_at desc",
                Long.class,
                faqArtifactId,
                comparisonArtifactId
        );
        assertThat(artifactIds).containsExactly(comparisonArtifactId, faqArtifactId);
    }

    @Test
    void regenerateFailureShouldKeepLatestSuccessfulArtifactReady() throws Exception {
        String ownerToken = registerAndGetToken("phase8_regen_fail_" + System.nanoTime());
        JsonNode project = createProject(ownerToken, "Regenerate fail project", null, null);
        Long projectId = project.path("data").path("id").asLong();
        Long spaceId = project.path("data").path("spaceId").asLong();

        JsonNode source = addTextSource(
                ownerToken,
                projectId,
                "Regenerate fail source",
                """
                RAG combines retrieval and generation.
                A vector store keeps embeddings for semantic search.
                """
        );
        Long sourceId = source.path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(articleJson(sourceId)))
                .willReturn(llmResponse(conceptJson(sourceId)))
                .willReturn(llmResponse("""
                        # Stable Report

                        Initial artifact content that should remain usable.
                        """))
                .willThrow(new RuntimeException("llm boom"));

        Long compileTaskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(compileTaskId, TaskStatus.SUCCESS);

        JsonNode studioTask = createStudioTask(ownerToken, """
                {
                  "spaceId": %d,
                  "researchProjectId": %d,
                  "taskType": "ARTIFACT_GENERATE",
                  "sourceScopeType": "RESEARCH_PROJECT",
                  "sourceIds": [%d],
                  "params": {
                    "artifactType": "REPORT",
                    "topic": "Stable Report"
                  }
                }
                """.formatted(spaceId, projectId, projectId));

        Long artifactId = studioTask.path("data").path("artifactId").asLong();
        Long firstTaskId = studioTask.path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(firstTaskId, TaskStatus.SUCCESS);

        JsonNode regenerate = objectMapper.readTree(mockMvc.perform(post("/api/v1/artifacts/{artifactId}/regenerate", artifactId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "params": {
                                    "topic": "Broken retry"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        Long regenerateTaskId = regenerate.path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(regenerateTaskId, TaskStatus.FAILED);

        mockMvc.perform(get("/api/v1/artifacts/{artifactId}", artifactId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.latestVersionNo").value(1))
                .andExpect(jsonPath("$.data.title").value("Stable Report"))
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("Initial artifact content that should remain usable.")));

        assertThat(jdbcTemplate.queryForObject(
                "select status from artifact where id = ?",
                String.class,
                artifactId
        )).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from artifact_version where artifact_id = ?",
                Integer.class,
                artifactId
        )).isEqualTo(1);
    }

    private JsonNode createStudioTask(String token, String payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/studio/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Long askQuestion(String token, Long sessionId, String question) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"%s"}
                                """.formatted(question)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("assistantMessageId").asLong();
    }

    private JsonNode compileSource(String token, Long sourceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/personal/sources/{sourceId}/compile", sourceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode createProject(String token, String title, String description, String goal) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", title);
        payload.put("description", description);
        payload.put("researchGoal", goal);
        MvcResult result = mockMvc.perform(post("/api/v1/personal/research-projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode addTextSource(String token, Long projectId, String title, String content) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", title);
        payload.put("content", content);
        MvcResult result = mockMvc.perform(post("/api/v1/personal/research-projects/{projectId}/sources/text", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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

    private Long createKnowledgeBase(String token, Long spaceId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/knowledge-bases", spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"phase8 kb"}
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
                                {"name":"%s","description":"phase8 team"}
                                """.formatted(spaceName)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private String registerAndGetToken(String username) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("email", username + "@example.com");
        payload.put("password", "Password123!");
        payload.put("displayName", "Phase 8 User");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
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

    private LlmResponse llmResponse(String content) {
        return LlmResponse.builder()
                .provider("test")
                .model("phase8-test")
                .content(content)
                .inputTokens(32)
                .outputTokens(64)
                .latencyMs(1L)
                .build();
    }

    private String articleJson(Long sourceId) {
        return """
                {
                  "title": "RAG project notes",
                  "summary": "A concise overview of retrieval-augmented generation basics.",
                  "keyPoints": ["RAG combines retrieval and generation", "Vector stores keep embeddings"],
                  "tags": ["RAG", "Vector Search"],
                  "evidenceQuotes": [
                    {
                      "quote": "RAG combines retrieval and generation.",
                      "sourceId": %d,
                      "reason": "Defines the central topic."
                    }
                  ]
                }
                """.formatted(sourceId);
    }

    private String conceptJson(Long sourceId) {
        return """
                {
                  "concepts": [
                    {
                      "name": "RAG",
                      "aliases": ["Retrieval-Augmented Generation"],
                      "definition": "A pattern that augments generation with retrieval.",
                      "explanation": "RAG fetches relevant context before generation.",
                      "useCases": ["Grounded assistants"],
                      "commonMisunderstandings": ["It removes the need for retrieval quality work"],
                      "evidence": {
                        "sourceId": %d,
                        "quote": "RAG combines retrieval and generation."
                      },
                      "confidence": 0.95
                    },
                    {
                      "name": "Vector Store",
                      "aliases": ["Embedding Index"],
                      "definition": "A storage layer for vector embeddings.",
                      "explanation": "It supports semantic retrieval over embeddings.",
                      "useCases": ["Semantic search"],
                      "commonMisunderstandings": ["It is the same as a relational database"],
                      "evidence": {
                        "sourceId": %d,
                        "quote": "A vector store keeps embeddings for semantic search."
                      },
                      "confidence": 0.90
                    }
                  ],
                  "relations": [
                    {
                      "sourceName": "RAG",
                      "targetName": "Vector Store",
                      "relationType": "USES",
                      "description": "RAG commonly relies on a vector store for retrieval."
                    }
                  ]
                }
                """.formatted(sourceId, sourceId);
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
