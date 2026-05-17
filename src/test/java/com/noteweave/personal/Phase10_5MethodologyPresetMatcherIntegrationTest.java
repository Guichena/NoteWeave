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
import com.noteweave.personal.methodology.MethodologySeedService;
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
class Phase10_5MethodologyPresetMatcherIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskDispatcher taskDispatcher;

    @Autowired
    private MethodologySeedService methodologySeedService;

    @MockBean
    private LlmClient llmClient;

    @Test
    void presetCardsShouldSeedIdempotentlyAndListReadableTemplates() throws Exception {
        String ownerToken = registerAndGetToken("phase10_5_seed_owner_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Methodology seed project", null, null);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from methodology_card where card_source = 'PRESET' and status = 'ACTIVE'",
                Integer.class
        )).isEqualTo(5);

        methodologySeedService.seedPresetCards();

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from methodology_card where card_source = 'PRESET' and status = 'ACTIVE'",
                Integer.class
        )).isEqualTo(5);

        MvcResult result = mockMvc.perform(get("/api/v1/personal/research-projects/{projectId}/methodology-cards", projectId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("Research Report Methodology")
                .contains("Study Guide Methodology")
                .contains("Comparison Analysis Methodology")
                .contains("Work Prep STAR Methodology")
                .contains("General Structured Writing Methodology")
                .contains("\"problemType\":\"REPORT\"")
                .contains("\"cardSource\":\"PRESET\"");
    }

    @Test
    void reportGenerationShouldInjectMatchedMethodologyIntoPrompt() throws Exception {
        String ownerToken = registerAndGetToken("phase10_5_prompt_owner_" + System.nanoTime());
        JsonNode project = createProjectResponse(ownerToken, "Methodology prompt project", "prompt", "report");
        Long projectId = project.path("data").path("id").asLong();
        Long spaceId = project.path("data").path("spaceId").asLong();

        Long sourceId = addTextSource(ownerToken, projectId, "Prompt source", """
                RAG combines retrieval and generation.
                Good reports should keep claims grounded in evidence.
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
                        # RAG Technical Report

                        Methodology-guided report output.
                        """));

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
                    "includeCitations": true
                  }
                }
                """.formatted(spaceId, projectId, projectId));

        Long taskId = studioTask.path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.SUCCESS);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> promptCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(llmClient).chat(promptCaptor.capture(), any());
        String prompt = promptCaptor.getValue().get(0).content();

        assertThat(prompt)
                .contains("Selected methodology: Research Report Methodology")
                .contains("Workflow:")
                .contains("Output structure:")
                .contains("Quality checklist:")
                .contains("Clarify the research question and scope")
                .contains("Executive Summary")
                .contains("Every major claim is grounded in the provided evidence");
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
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String registerAndGetToken(String username) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("email", username + "@example.com");
        payload.put("password", "Password123!");
        payload.put("displayName", "Phase 10.5 User");
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
                .model("phase10_5-test")
                .content(content)
                .inputTokens(24)
                .outputTokens(48)
                .latencyMs(1L)
                .build();
    }

    private String articleJson(Long sourceId) {
        return """
                {
                  "title": "Prompt source",
                  "summary": "A concise overview of evidence-grounded report writing.",
                  "keyPoints": ["RAG combines retrieval and generation", "Claims should stay grounded"],
                  "tags": ["RAG", "Report"],
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
                      "definition": "A grounded generation pattern.",
                      "explanation": "RAG pulls evidence before generating an answer.",
                      "useCases": ["Research reports"],
                      "commonMisunderstandings": ["It removes the need to cite evidence"],
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
