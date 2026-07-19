package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
@ActiveProfiles("test")
class QaWorkspaceRetrievalStrategyContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void workspaceSettingsMustNotChangeTheSingleV2ProfileForNewQaRuns() throws Exception {
        String workspaceA = createWorkspace("QA strategy A");
        String workspaceB = createWorkspace("QA strategy B");
        String conversationA = createConversation(workspaceA);
        String conversationB = createConversation(workspaceB);

        String initialRunA = sendQa(conversationA, "initial-a");
        String initialRunB = sendQa(conversationB, "initial-b");
        assertV2Run(initialRunA);
        assertV2Run(initialRunB);

        setV2(workspaceA, false);
        String falseSettingRunA = sendQa(conversationA, "false-setting-a");
        String unaffectedRunB = sendQa(conversationB, "unaffected-b");
        assertV2Run(falseSettingRunA);
        assertV2Run(unaffectedRunB);

        setV2(workspaceA, true);
        String trueSettingRunA = sendQa(conversationA, "true-setting-a");
        assertV2Run(trueSettingRunA);

        // A persisted run stays V2 even after a legacy setting write.
        assertV2Run(initialRunA);
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

    private void setV2(String workspaceId, boolean enabled) throws Exception {
        mockMvc.perform(put("/api/v2/workspaces/{workspaceId}/retrieval-settings", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "retrieval_strategy_v2_enabled", enabled))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrieval_strategy_v2_enabled").value(enabled));
    }

    private void assertV2Run(String runId) throws Exception {
        String expectedPlanVersion = "qa-weknora-hybrid-v1";
        String expectedProfile = "qa-weknora-hybrid-v1";
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
                .isEqualTo("qa-source-diverse-budget-v2");

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
                .isEqualTo("qa-source-diverse-budget-v2");
        assertThat(summary.path("execution_trace").path("steps").get(0)
                .path("measurements").path("strategy_v2_enabled").asInt())
                .isEqualTo(1);
    }
}
