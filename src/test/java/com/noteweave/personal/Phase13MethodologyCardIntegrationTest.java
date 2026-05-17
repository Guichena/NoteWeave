package com.noteweave.personal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class Phase13MethodologyCardIntegrationTest extends ContainerizedIntegrationTest {

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
    void methodologyCardCrudShouldSupportVersioningArchiveAndOwnerOnlyAccess() throws Exception {
        String ownerToken = registerAndGetToken("phase13_method_owner_" + System.nanoTime());
        String outsiderToken = registerAndGetToken("phase13_method_outsider_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Methodology CRUD project", "phase13", "custom methodology");

        JsonNode created = readJson(mockMvc.perform(post("/api/v1/personal/research-projects/{projectId}/methodology-cards", projectId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "My Project Report Methodology",
                                  "scene": "internal research report",
                                  "problemType": "REPORT",
                                  "workflow": ["Define question", "Collect evidence", "Write conclusion"],
                                  "requiredConcepts": ["RAG", "Citations"],
                                  "outputStructure": ["Background", "Findings", "Conclusion"],
                                  "qualityChecklist": ["Claims grounded", "Trade-offs explicit"],
                                  "cardScope": "PROJECT"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cardSource").value("USER_CREATED"))
                .andExpect(jsonPath("$.data.cardScope").value("PROJECT"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.createdBy").exists())
                .andReturn());

        long cardId = created.path("data").path("id").asLong();

        mockMvc.perform(get("/api/v1/personal/methodology-cards/{cardId}", cardId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("My Project Report Methodology"))
                .andExpect(jsonPath("$.data.requiredConcepts[0]").value("RAG"));

        mockMvc.perform(get("/api/v1/personal/methodology-cards/{cardId}", cardId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("METHODOLOGY_CARD_ACCESS_DENIED"));

        JsonNode updated = readJson(mockMvc.perform(put("/api/v1/personal/methodology-cards/{cardId}", cardId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "My Project Report Methodology v2",
                                  "scene": "internal research report",
                                  "problemType": "REPORT",
                                  "workflow": ["Define question", "Collect stronger evidence", "Write conclusion"],
                                  "requiredConcepts": ["RAG", "Citations"],
                                  "outputStructure": ["Executive Summary", "Findings", "Conclusion"],
                                  "qualityChecklist": ["Claims grounded", "Trade-offs explicit", "Open questions listed"],
                                  "cardScope": "PROJECT",
                                  "researchProjectId": %d
                                }
                                """.formatted(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andReturn());

        assertThat(updated.path("data").path("name").asText()).isEqualTo("My Project Report Methodology v2");

        mockMvc.perform(delete("/api/v1/personal/methodology-cards/{cardId}", cardId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/personal/methodology-cards/{cardId}", cardId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.data.version").value(2));

        JsonNode listed = readJson(mockMvc.perform(get("/api/v1/personal/research-projects/{projectId}/methodology-cards", projectId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(listed.path("data"))
                .allMatch(item -> item.path("id").asLong() != cardId);
    }

    @Test
    void archivedMethodologyShouldStopMatchingAndCustomCardsShouldOverridePresetBeforeArchive() throws Exception {
        String ownerToken = registerAndGetToken("phase13_match_owner_" + System.nanoTime());
        JsonNode project = createProjectResponse(ownerToken, "Methodology match project", "phase13", "comparison");
        Long projectId = project.path("data").path("id").asLong();
        Long spaceId = project.path("data").path("spaceId").asLong();

        Long sourceId = addTextSource(ownerToken, projectId, "Comparison source", """
                Option A is simpler to operate.
                Option B offers stronger retrieval quality at higher cost.
                """).path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(articleJson(sourceId)))
                .willReturn(llmResponse(conceptJson(sourceId)));

        Long compileTaskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(compileTaskId, TaskStatus.SUCCESS);

        JsonNode created = readJson(mockMvc.perform(post("/api/v1/personal/research-projects/{projectId}/methodology-cards", projectId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Custom Comparison Methodology",
                                  "scene": "technical solution comparison",
                                  "problemType": "COMPARISON",
                                  "workflow": ["List options", "Score dimensions", "Make recommendation"],
                                  "requiredConcepts": ["Latency", "Cost"],
                                  "outputStructure": ["Context", "Custom Matrix", "Recommendation"],
                                  "qualityChecklist": ["Same dimensions", "Explicit trade-offs"],
                                  "cardScope": "SPACE"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());
        long cardId = created.path("data").path("id").asLong();

        clearInvocations(llmClient);
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("# Custom Comparison\n\nUse the custom structure."));

        JsonNode firstTask = createStudioTask(ownerToken, """
                {
                  "spaceId": %d,
                  "researchProjectId": %d,
                  "taskType": "ARTIFACT_GENERATE",
                  "sourceScopeType": "RESEARCH_PROJECT",
                  "sourceIds": [%d],
                  "params": {
                    "artifactType": "COMPARISON",
                    "topic": "Retrieval Options Comparison",
                    "scenario": "technical solution comparison",
                    "includeCitations": true
                  }
                }
                """.formatted(spaceId, projectId, projectId));
        Long firstTaskId = firstTask.path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(firstTaskId, TaskStatus.SUCCESS);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> firstPromptCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(llmClient).chat(firstPromptCaptor.capture(), any());
        String firstPrompt = firstPromptCaptor.getValue().get(0).content();
        assertThat(firstPrompt)
                .contains("Selected methodology: Custom Comparison Methodology")
                .contains("Custom Matrix")
                .contains("Explicit trade-offs");

        mockMvc.perform(delete("/api/v1/personal/methodology-cards/{cardId}", cardId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        clearInvocations(llmClient);
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("# Preset Comparison\n\nFallback to preset."));

        JsonNode secondTask = createStudioTask(ownerToken, """
                {
                  "spaceId": %d,
                  "researchProjectId": %d,
                  "taskType": "ARTIFACT_GENERATE",
                  "sourceScopeType": "RESEARCH_PROJECT",
                  "sourceIds": [%d],
                  "params": {
                    "artifactType": "COMPARISON",
                    "topic": "Retrieval Options Comparison Again",
                    "scenario": "technical solution comparison",
                    "includeCitations": true
                  }
                }
                """.formatted(spaceId, projectId, projectId));
        Long secondTaskId = secondTask.path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(secondTaskId, TaskStatus.SUCCESS);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> secondPromptCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(llmClient).chat(secondPromptCaptor.capture(), any());
        String secondPrompt = secondPromptCaptor.getValue().get(0).content();
        assertThat(secondPrompt)
                .contains("Selected methodology: Comparison Analysis Methodology")
                .doesNotContain("Selected methodology: Custom Comparison Methodology");
    }

    private JsonNode createStudioTask(String token, String payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/studio/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result);
    }

    private JsonNode compileSource(String token, Long sourceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/personal/sources/{sourceId}/compile", sourceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result);
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
        return readJson(result);
    }

    private Long createProject(String token, String title, String description, String goal) throws Exception {
        return createProjectResponse(token, title, description, goal).path("data").path("id").asLong();
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
        return readJson(result);
    }

    private String registerAndGetToken(String username) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("email", username + "@example.com");
        payload.put("password", "Password123!");
        payload.put("displayName", "Phase 13 User");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("accessToken").asText();
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
                .model("phase13-test")
                .content(content)
                .inputTokens(24)
                .outputTokens(48)
                .latencyMs(1L)
                .build();
    }

    private String articleJson(Long sourceId) {
        return """
                {
                  "title": "Comparison source",
                  "summary": "Comparison evidence for retrieval options.",
                  "keyPoints": ["Option A is simpler", "Option B has higher quality and cost"],
                  "tags": ["Comparison", "Retrieval"],
                  "evidenceQuotes": [
                    {
                      "quote": "Option A is simpler to operate.",
                      "sourceId": %d,
                      "reason": "Explains the operational trade-off."
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
                      "name": "Retrieval Trade-off",
                      "aliases": ["Search trade-off"],
                      "definition": "Comparing simplicity, quality and cost.",
                      "explanation": "Different retrieval options optimize different dimensions.",
                      "useCases": ["Technical comparison"],
                      "commonMisunderstandings": ["One option is always best"],
                      "evidence": {
                        "sourceId": %d,
                        "quote": "Option B offers stronger retrieval quality at higher cost."
                      },
                      "confidence": 0.92
                    }
                  ],
                  "relations": []
                }
                """.formatted(sourceId);
    }
}
