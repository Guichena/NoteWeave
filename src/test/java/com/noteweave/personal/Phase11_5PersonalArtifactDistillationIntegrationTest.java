package com.noteweave.personal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
class Phase11_5PersonalArtifactDistillationIntegrationTest extends ContainerizedIntegrationTest {

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
    void distillationShouldRequireConfirmationAndCreateTraceableSynthesisArtifacts() throws Exception {
        String ownerToken = registerAndGetToken("phase11_5_owner_" + System.nanoTime());
        String outsiderToken = registerAndGetToken("phase11_5_outsider_" + System.nanoTime());
        JsonNode project = createProjectResponse(ownerToken, "Distill project", "phase11.5", "distill");
        Long projectId = project.path("data").path("id").asLong();
        Long spaceId = project.path("data").path("spaceId").asLong();

        Long artifactId = createPersonalArtifact(ownerToken, projectId, spaceId, "Artifact to Distill");

        mockMvc.perform(post("/api/v1/artifacts/{artifactId}/distill-to-personal-wiki", artifactId)
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardType": "SYNTHESIS",
                                  "confirm": false
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ARTIFACT_ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/artifacts/{artifactId}/card-relations", artifactId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        JsonNode proposal = readJson(mockMvc.perform(post("/api/v1/artifacts/{artifactId}/distill-to-personal-wiki", artifactId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardType": "SYNTHESIS",
                                  "confirm": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmed").value(false))
                .andExpect(jsonPath("$.data.proposalId").isNumber())
                .andExpect(jsonPath("$.data.artifactVersionId").isNumber())
                .andExpect(jsonPath("$.data.title").value("Artifact to Distill"))
                .andReturn());

        Long proposalId = proposal.path("data").path("proposalId").asLong();
        Long artifactVersionId = proposal.path("data").path("artifactVersionId").asLong();

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from synthesis_card where source_artifact_id = ?",
                Integer.class,
                artifactId
        )).isZero();

        JsonNode confirmed = readJson(mockMvc.perform(post("/api/v1/artifacts/{artifactId}/distill-to-personal-wiki", artifactId)
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
                .andExpect(jsonPath("$.data.confirmed").value(true))
                .andExpect(jsonPath("$.data.synthesisCard.id").isNumber())
                .andExpect(jsonPath("$.data.synthesisCard.sourceArtifactId").value(artifactId))
                .andExpect(jsonPath("$.data.synthesisCard.sourceArtifactVersionId").value(artifactVersionId))
                .andExpect(jsonPath("$.data.synthesisCard.citations[0].sourceType").value("SOURCE"))
                .andReturn());

        Long synthesisCardId = confirmed.path("data").path("synthesisCard").path("id").asLong();

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from synthesis_card where id = ? and source_artifact_id = ? and source_artifact_version_id = ?",
                Integer.class,
                synthesisCardId,
                artifactId,
                artifactVersionId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from artifact_card_relation where artifact_id = ? and artifact_version_id = ? and card_type = 'SYNTHESIS' and card_id = ? and relation_type = 'SUMMARIZED_INTO'",
                Integer.class,
                artifactId,
                artifactVersionId,
                synthesisCardId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from synthesis_card_citation where synthesis_card_id = ?",
                Integer.class,
                synthesisCardId
        )).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from synthesis_concept_relation where synthesis_card_id = ?",
                Integer.class,
                synthesisCardId
        )).isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/api/v1/artifacts/{artifactId}/card-relations", artifactId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].cardType").value("SYNTHESIS"))
                .andExpect(jsonPath("$.data[0].relationType").value("SUMMARIZED_INTO"))
                .andExpect(jsonPath("$.data[0].artifactVersionId").value(artifactVersionId))
                .andExpect(jsonPath("$.data[0].cardId").value(synthesisCardId));

        mockMvc.perform(get("/api/v1/personal/research-projects/{projectId}/synthesis-cards", projectId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(synthesisCardId))
                .andExpect(jsonPath("$.data[0].sourceArtifactId").value(artifactId))
                .andExpect(jsonPath("$.data[0].sourceArtifactVersionId").value(artifactVersionId));

        mockMvc.perform(get("/api/v1/personal/synthesis-cards/{cardId}", synthesisCardId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(synthesisCardId))
                .andExpect(jsonPath("$.data.title").value("Artifact to Distill"))
                .andExpect(jsonPath("$.data.citations[0].sourceType").value("SOURCE"))
                .andExpect(jsonPath("$.data.conceptRelations[0].relationType").value("RELATED"));
    }

    @Test
    void confirmShouldRejectStaleProposalAndBeIdempotentPerArtifactVersion() throws Exception {
        String ownerToken = registerAndGetToken("phase11_5_version_owner_" + System.nanoTime());
        JsonNode project = createProjectResponse(ownerToken, "Versioned distill project", "phase11.5", "version");
        Long projectId = project.path("data").path("id").asLong();
        Long spaceId = project.path("data").path("spaceId").asLong();

        Long artifactId = createPersonalArtifact(ownerToken, projectId, spaceId, "Versioned Artifact");

        JsonNode firstProposal = readJson(mockMvc.perform(post("/api/v1/artifacts/{artifactId}/distill-to-personal-wiki", artifactId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardType": "SYNTHESIS",
                                  "confirm": false
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());
        Long firstProposalId = firstProposal.path("data").path("proposalId").asLong();

        mockMvc.perform(put("/api/v1/artifacts/{artifactId}", artifactId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Versioned Artifact",
                                  "content": "# Versioned Artifact\\n\\nUpdated content for version two.",
                                  "changeNote": "v2"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestVersionNo").value(2));

        Long latestVersionId = jdbcTemplate.queryForObject(
                "select id from artifact_version where artifact_id = ? order by version_no desc limit 1",
                Long.class,
                artifactId
        );

        mockMvc.perform(post("/api/v1/artifacts/{artifactId}/distill-to-personal-wiki", artifactId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardType": "SYNTHESIS",
                                  "proposalId": %d,
                                  "confirm": true
                                }
                                """.formatted(firstProposalId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ARTIFACT_DISTILLATION_PROPOSAL_STALE"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from synthesis_card where source_artifact_id = ?",
                Integer.class,
                artifactId
        )).isZero();

        JsonNode secondProposal = readJson(mockMvc.perform(post("/api/v1/artifacts/{artifactId}/distill-to-personal-wiki", artifactId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardType": "SYNTHESIS",
                                  "confirm": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.artifactVersionId").value(latestVersionId))
                .andReturn());
        Long secondProposalId = secondProposal.path("data").path("proposalId").asLong();

        JsonNode firstConfirm = readJson(mockMvc.perform(post("/api/v1/artifacts/{artifactId}/distill-to-personal-wiki", artifactId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardType": "SYNTHESIS",
                                  "proposalId": %d,
                                  "confirm": true
                                }
                                """.formatted(secondProposalId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmed").value(true))
                .andExpect(jsonPath("$.data.synthesisCard.sourceArtifactVersionId").value(latestVersionId))
                .andReturn());

        Long synthesisCardId = firstConfirm.path("data").path("synthesisCard").path("id").asLong();

        mockMvc.perform(post("/api/v1/artifacts/{artifactId}/distill-to-personal-wiki", artifactId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardType": "SYNTHESIS",
                                  "proposalId": %d,
                                  "confirm": true
                                }
                                """.formatted(secondProposalId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmed").value(true))
                .andExpect(jsonPath("$.data.synthesisCard.id").value(synthesisCardId));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from synthesis_card where source_artifact_id = ? and source_artifact_version_id = ?",
                Integer.class,
                artifactId,
                latestVersionId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from artifact_card_relation where artifact_id = ? and artifact_version_id = ? and card_type = 'SYNTHESIS' and relation_type = 'SUMMARIZED_INTO'",
                Integer.class,
                artifactId,
                latestVersionId
        )).isEqualTo(1);
    }

    private Long createPersonalArtifact(String token, Long projectId, Long spaceId, String topic) throws Exception {
        Long sourceId = addTextSource(token, projectId, topic + " source", """
                Retrieval needs evidence.
                Distilled notes should preserve citations.
                """).path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(articleJson(sourceId, topic + " source")))
                .willReturn(llmResponse(conceptJson(sourceId)));

        Long compileTaskId = compileSource(token, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(compileTaskId, TaskStatus.SUCCESS);

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("""
                        # %s

                        ## Summary
                        Retrieval needs evidence-backed notes.

                        ## Key Takeaways
                        - Distilled notes should preserve citations.
                        - Personal wiki writeback must stay explicit.
                        """.formatted(topic)));

        JsonNode studioTask = createStudioTask(token, """
                {
                  "spaceId": %d,
                  "researchProjectId": %d,
                  "taskType": "ARTIFACT_GENERATE",
                  "sourceScopeType": "RESEARCH_PROJECT",
                  "sourceIds": [%d],
                  "params": {
                    "artifactType": "READING_NOTES",
                    "topic": "%s",
                    "includeCitations": true
                  }
                }
                """.formatted(spaceId, projectId, projectId, topic));

        Long artifactId = studioTask.path("data").path("artifactId").asLong();
        Long taskId = studioTask.path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.SUCCESS);
        return artifactId;
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
        payload.put("displayName", "Phase 11.5 User");
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
                .model("phase11_5-test")
                .content(content)
                .inputTokens(24)
                .outputTokens(48)
                .latencyMs(1L)
                .build();
    }

    private String articleJson(Long sourceId, String title) {
        return """
                {
                  "title": "%s",
                  "summary": "A concise source summary for distillation tests.",
                  "keyPoints": ["Retrieval needs evidence", "Citations stay traceable"],
                  "tags": ["Distillation", "Evidence"],
                  "evidenceQuotes": [
                    {
                      "quote": "Retrieval needs evidence.",
                      "sourceId": %d,
                      "reason": "Supports the core note."
                    }
                  ]
                }
                """.formatted(title, sourceId);
    }

    private String conceptJson(Long sourceId) {
        return """
                {
                  "concepts": [
                    {
                      "name": "Evidence Traceability",
                      "aliases": ["Traceable Evidence"],
                      "definition": "Evidence should remain backtraceable after distillation.",
                      "explanation": "Traceable evidence protects personal wiki quality.",
                      "useCases": ["Reading notes"],
                      "commonMisunderstandings": ["JSON cache is enough"],
                      "evidence": {
                        "sourceId": %d,
                        "quote": "Distilled notes should preserve citations."
                      },
                      "confidence": 0.95
                    }
                  ],
                  "relations": []
                }
                """.formatted(sourceId);
    }
}
