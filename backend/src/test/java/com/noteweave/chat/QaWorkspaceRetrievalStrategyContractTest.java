package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "test-es"})
class QaWorkspaceRetrievalStrategyContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void newQaRunsUseTheCurrentRetrievalProfile() throws Exception {
        String workspaceId = createWorkspace("QA strategy");
        String conversationId = createConversation(workspaceId);
        assertCurrentRun(sendQa(conversationId, "current-profile"));
    }

    private String createWorkspace(String name) throws Exception {
        String response = mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("workspace_id").asText();
    }

    private String createConversation(String workspaceId) throws Exception {
        String response = mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"QA profile\",\"conversation_type\":\"QA\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("conversation_id").asText();
    }

    private String sendQa(String conversationId, String requestId) throws Exception {
        String response = mockMvc.perform(post(
                        "/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "Which evidence supports " + requestId + "?",
                                "answer_mode", "QA",
                                "client_request_id", requestId + "-" + UUID.randomUUID()
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("answer_run_id").asText();
    }

    private void assertCurrentRun(String runId) throws Exception {
        String expectedPlanVersion = "qa-weknora-hybrid-v1";
        String expectedProfile = "qa-hybrid-mmr-token-v3";
        String expectedRelevancePolicy = "qa-lexical-sufficiency-v2";
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select retrieval_plan_version, retrieval_plan_json
                from answer_run
                where id = ?
                """, runId);
        assertThat(row.get("retrieval_plan_version")).isEqualTo(expectedPlanVersion);
        JsonNode plan = objectMapper.readTree(row.get("retrieval_plan_json").toString());
        JsonNode filters = plan.path("steps").get(0).path("filters");
        assertThat(plan.path("version").asText()).isEqualTo(expectedPlanVersion);
        assertThat(filters.path("strategy_profile").asText()).isEqualTo(expectedProfile);
        assertThat(filters.path("relevance_policy").asText()).isEqualTo(expectedRelevancePolicy);
        assertThat(filters.path("selection_policy").asText())
                .isEqualTo("qa-mmr-token-budget-v3");

        String promptVersion = jdbcTemplate.queryForObject("""
                select prompt_version
                from message_revision
                where answer_run_id = ? and revision_no = 1
                """, String.class, runId);
        assertThat(promptVersion).isEqualTo(expectedPlanVersion);

        String summaryJson = jdbcTemplate.queryForObject("""
                select payload_json
                from answer_event
                where answer_run_id = ? and event_type = 'retrieval.summary'
                """, String.class, runId);
        JsonNode summary = objectMapper.readTree(summaryJson);
        assertThat(summary.path("plan_version").asText()).isEqualTo(expectedPlanVersion);
        assertThat(summary.path("strategy_profile").asText()).isEqualTo(expectedProfile);
        assertThat(summary.path("relevance_policy").asText()).isEqualTo(expectedRelevancePolicy);
        assertThat(summary.path("selection_policy").asText())
                .isEqualTo("qa-mmr-token-budget-v3");
        assertThat(summary.path("execution_trace").path("steps").get(0)
                .path("measurements").path("strategy_v2_enabled").asInt())
                .isEqualTo(1);
    }
}
