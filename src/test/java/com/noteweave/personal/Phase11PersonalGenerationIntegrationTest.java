package com.noteweave.personal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.llm.dto.LlmMessage;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.service.LlmClient;
import com.noteweave.support.ContainerizedIntegrationTest;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.service.TaskDispatcher;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Phase11PersonalGenerationIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskDispatcher taskDispatcher;

    @MockBean
    private LlmClient llmClient;

    @Test
    void workPrepGenerationShouldUseMethodologyKeepCardsStableAndPersistTraceableSources() throws Exception {
        String ownerToken = registerAndGetToken("phase11_workprep_owner_" + System.nanoTime());
        String outsiderToken = registerAndGetToken("phase11_workprep_outsider_" + System.nanoTime());
        JsonNode project = createProjectResponse(ownerToken, "Work prep project", "phase11", "interview prep");
        Long projectId = project.path("data").path("id").asLong();
        Long spaceId = project.path("data").path("spaceId").asLong();

        Long sourceId = addTextSource(ownerToken, projectId, "Interview source", """
                RAG combines retrieval and generation.
                STAR answers should describe situation, task, action and result.
                """).path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(articleJson(sourceId)))
                .willReturn(llmResponse(conceptJson(sourceId)));

        Long compileTaskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(compileTaskId, TaskStatus.SUCCESS);

        int articleCountBefore = jdbcTemplate.queryForObject(
                "select count(*) from article_card where research_project_id = ?",
                Integer.class,
                projectId
        );
        int conceptCountBefore = jdbcTemplate.queryForObject(
                "select count(*) from concept_card where research_project_id = ?",
                Integer.class,
                projectId
        );

        clearInvocations(llmClient);
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("""
                        # Work Prep for RAG Interview

                        ## STAR Opening
                        Situation: We needed grounded answers.

                        ## Core Answer
                        Explain retrieval, generation and evidence traceability.
                        """));

        JsonNode studioTask = createStudioTask(ownerToken, """
                {
                  "spaceId": %d,
                  "researchProjectId": %d,
                  "taskType": "ARTIFACT_GENERATE",
                  "sourceScopeType": "RESEARCH_PROJECT",
                  "sourceIds": [%d],
                  "params": {
                    "artifactType": "WORK_PREP",
                    "topic": "Work Prep for RAG Interview",
                    "scenario": "behavioral interview",
                    "targetRole": "Java Backend Engineer",
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
                .andExpect(jsonPath("$.data.artifactType").value("WORK_PREP"))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.citations[0].sourceType").value("SOURCE"));

        mockMvc.perform(get("/api/v1/tasks/{taskId}/skill-logs", taskId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].skillName").value("GenerateWorkPrepSkill"));

        mockMvc.perform(get("/api/v1/artifacts/{artifactId}", artifactId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ARTIFACT_ACCESS_DENIED"));

        List<String> sourceTypes = jdbcTemplate.queryForList(
                "select source_type from artifact_source where artifact_id = ? order by source_type asc",
                String.class,
                artifactId
        );
        assertThat(sourceTypes)
                .contains("ARTICLE_CARD", "CONCEPT_CARD", "SOURCE");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from artifact_citation where artifact_id = ?",
                Integer.class,
                artifactId
        )).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from article_card where research_project_id = ?",
                Integer.class,
                projectId
        )).isEqualTo(articleCountBefore);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from concept_card where research_project_id = ?",
                Integer.class,
                projectId
        )).isEqualTo(conceptCountBefore);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> promptCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(llmClient).chat(promptCaptor.capture(), any());
        String prompt = promptCaptor.getValue().get(0).content();
        assertThat(prompt)
                .contains("Selected methodology: Work Prep STAR Methodology")
                .contains("STAR")
                .doesNotContain("synthesis_card");
    }

    @Test
    void readingNotesGenerationShouldBeSupportedForPersonalProjectArtifacts() throws Exception {
        String ownerToken = registerAndGetToken("phase11_notes_owner_" + System.nanoTime());
        JsonNode project = createProjectResponse(ownerToken, "Reading notes project", "phase11", "notes");
        Long projectId = project.path("data").path("id").asLong();
        Long spaceId = project.path("data").path("spaceId").asLong();

        Long sourceId = addTextSource(ownerToken, projectId, "Reading source", """
                Notes should capture what changed the reader's understanding.
                Evidence still needs to stay traceable.
                """).path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(articleJson(sourceId)))
                .willReturn(llmResponse(conceptJson(sourceId)));

        Long compileTaskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(compileTaskId, TaskStatus.SUCCESS);

        clearInvocations(llmClient);
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("""
                        # Reading Notes on Evidence

                        ## What changed
                        Traceable evidence keeps notes trustworthy.
                        """));

        JsonNode studioTask = createStudioTask(ownerToken, """
                {
                  "spaceId": %d,
                  "researchProjectId": %d,
                  "taskType": "ARTIFACT_GENERATE",
                  "sourceScopeType": "RESEARCH_PROJECT",
                  "sourceIds": [%d],
                  "params": {
                    "artifactType": "READING_NOTES",
                    "topic": "Reading Notes on Evidence",
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
                .andExpect(jsonPath("$.data.artifactType").value("READING_NOTES"))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.latestVersionNo").value(1));
    }

    @Test
    void personalGenerationShouldReuseConfirmedSynthesisCardsAsStableContext() throws Exception {
        String ownerToken = registerAndGetToken("phase11_synthesis_owner_" + System.nanoTime());
        JsonNode project = createProjectResponse(ownerToken, "Synthesis context project", "phase11", "synthesis");
        Long projectId = project.path("data").path("id").asLong();
        Long spaceId = project.path("data").path("spaceId").asLong();

        Long sourceId = addTextSource(ownerToken, projectId, "Synthesis source", """
                Retrieval-backed notes should become stable synthesis context after explicit confirmation.
                Stable synthesis should help later artifact generation stay consistent.
                """).path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(articleJson(sourceId)))
                .willReturn(llmResponse(conceptJson(sourceId)));

        Long compileTaskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(compileTaskId, TaskStatus.SUCCESS);

        clearInvocations(llmClient);
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("""
                        # Stable Synthesis Artifact

                        ## Key Takeaways
                        Stable synthesis should be reused by later generation tasks.
                        """));

        JsonNode seedArtifact = createStudioTask(ownerToken, """
                {
                  "spaceId": %d,
                  "researchProjectId": %d,
                  "taskType": "ARTIFACT_GENERATE",
                  "sourceScopeType": "RESEARCH_PROJECT",
                  "sourceIds": [%d],
                  "params": {
                    "artifactType": "READING_NOTES",
                    "topic": "Stable Synthesis Artifact",
                    "includeCitations": true
                  }
                }
                """.formatted(spaceId, projectId, projectId));

        Long seedArtifactId = seedArtifact.path("data").path("artifactId").asLong();
        Long seedTaskId = seedArtifact.path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(seedTaskId, TaskStatus.SUCCESS);

        JsonNode proposal = objectMapper.readTree(mockMvc.perform(post("/api/v1/artifacts/{artifactId}/distill-to-personal-wiki", seedArtifactId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardType": "SYNTHESIS",
                                  "confirm": false
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        long proposalId = proposal.path("data").path("proposalId").asLong();

        mockMvc.perform(post("/api/v1/artifacts/{artifactId}/distill-to-personal-wiki", seedArtifactId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardType": "SYNTHESIS",
                                  "proposalId": %d,
                                  "confirm": true
                                }
                                """.formatted(proposalId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmed").value(true));

        clearInvocations(llmClient);
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("""
                        # Follow-up Work Prep

                        ## Core Answer
                        Reuse the confirmed synthesis before expanding into new notes.
                        """));

        JsonNode studioTask = createStudioTask(ownerToken, """
                {
                  "spaceId": %d,
                  "researchProjectId": %d,
                  "taskType": "ARTIFACT_GENERATE",
                  "sourceScopeType": "RESEARCH_PROJECT",
                  "sourceIds": [%d],
                  "params": {
                    "artifactType": "WORK_PREP",
                    "topic": "Follow-up Work Prep",
                    "includeCitations": true
                  }
                }
                """.formatted(spaceId, projectId, projectId));

        Long artifactId = studioTask.path("data").path("artifactId").asLong();
        Long taskId = studioTask.path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.SUCCESS);

        List<String> sourceTypes = jdbcTemplate.queryForList(
                "select source_type from artifact_source where artifact_id = ? order by source_type asc",
                String.class,
                artifactId
        );
        assertThat(sourceTypes).contains("SYNTHESIS_CARD");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> promptCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(llmClient).chat(promptCaptor.capture(), any());
        String prompt = promptCaptor.getValue().get(0).content();
        assertThat(prompt)
                .contains("Stable Synthesis Artifact")
                .contains("Stable synthesis should be reused by later generation tasks.");
    }

    @Test
    void personalGenerationShouldFailWhenTraceableEvidenceIsMissingAndOutsiderCannotStartTask() throws Exception {
        String ownerToken = registerAndGetToken("phase11_fail_owner_" + System.nanoTime());
        String outsiderToken = registerAndGetToken("phase11_fail_outsider_" + System.nanoTime());
        JsonNode project = createProjectResponse(ownerToken, "Evidence failure project", "phase11", "report");
        Long projectId = project.path("data").path("id").asLong();
        Long spaceId = project.path("data").path("spaceId").asLong();

        Long sourceId = addTextSource(ownerToken, projectId, "Failure source", """
                Evidence is required for grounded artifact generation.
                """).path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(articleJson(sourceId)))
                .willReturn(llmResponse(conceptJson(sourceId)));

        Long compileTaskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(compileTaskId, TaskStatus.SUCCESS);

        jdbcTemplate.update("delete from article_card_citation where article_card_id in (select id from article_card where research_project_id = ?)", projectId);
        jdbcTemplate.update("delete from concept_card_citation where concept_card_id in (select id from concept_card where research_project_id = ?)", projectId);

        mockMvc.perform(post("/api/v1/studio/tasks")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "spaceId": %d,
                                  "researchProjectId": %d,
                                  "taskType": "ARTIFACT_GENERATE",
                                  "sourceScopeType": "RESEARCH_PROJECT",
                                  "sourceIds": [%d],
                                  "params": {
                                    "artifactType": "REPORT",
                                    "topic": "Illegal access"
                                  }
                                }
                                """.formatted(spaceId, projectId, projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SPACE_ACCESS_DENIED"));

        clearInvocations(llmClient);

        JsonNode studioTask = createStudioTask(ownerToken, """
                {
                  "spaceId": %d,
                  "researchProjectId": %d,
                  "taskType": "ARTIFACT_GENERATE",
                  "sourceScopeType": "RESEARCH_PROJECT",
                  "sourceIds": [%d],
                  "params": {
                    "artifactType": "REPORT",
                    "topic": "Evidence failure report",
                    "includeCitations": true
                  }
                }
                """.formatted(spaceId, projectId, projectId));

        Long artifactId = studioTask.path("data").path("artifactId").asLong();
        Long taskId = studioTask.path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.FAILED);

        assertThat(jdbcTemplate.queryForObject(
                "select status from artifact where id = ?",
                String.class,
                artifactId
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from artifact_citation where artifact_id = ?",
                Integer.class,
                artifactId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select error_message from task where id = ?",
                String.class,
                taskId
        )).containsIgnoringCase("evidence");
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

    private JsonNode compileSource(String token, Long sourceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/personal/sources/{sourceId}/compile", sourceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode createProjectResponse(String token, String title, String description, String goal) throws Exception {
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

    private String registerAndGetToken(String username) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("email", username + "@example.com");
        payload.put("password", "Password123!");
        payload.put("displayName", "Phase 11 User");
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
            String current = jdbcTemplate.queryForObject(
                    "select task_status from task where id = ?",
                    String.class,
                    taskId
            );
            if (expectedStatus.name().equals(current)) {
                return;
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
                .model("phase11-test")
                .content(content)
                .inputTokens(24)
                .outputTokens(48)
                .latencyMs(1L)
                .build();
    }

    private String articleJson(Long sourceId) {
        return """
                {
                  "title": "Interview source",
                  "summary": "A concise overview of evidence-grounded preparation.",
                  "keyPoints": ["RAG combines retrieval and generation", "STAR structures work prep answers"],
                  "tags": ["RAG", "Interview"],
                  "evidenceQuotes": [
                    {
                      "quote": "RAG combines retrieval and generation.",
                      "sourceId": %d,
                      "reason": "Defines the technical topic."
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
                      "definition": "A grounded generation pattern.",
                      "explanation": "RAG retrieves evidence before generating an answer.",
                      "useCases": ["Interview discussion", "Research reports"],
                      "commonMisunderstandings": ["It removes the need to cite sources"],
                      "evidence": {
                        "sourceId": %d,
                        "quote": "RAG combines retrieval and generation."
                      },
                      "confidence": 0.95
                    }
                  ],
                  "relations": []
                }
                """.formatted(sourceId);
    }
}
