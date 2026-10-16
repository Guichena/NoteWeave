package com.noteweave.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.RetrievalPlan;
import java.time.Instant;
import java.util.List;
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
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EvidenceSufficiencyContractTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AnswerRunService answerRunService;
    @Autowired
    private AnswerSubmissionService answerSubmissionService;

    @Test
    void historicalRunOmitsTheFieldAndAPreparedRunReturnsItOnBothEndpoints() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = UUID.randomUUID().toString();
        String queryMessageId = UUID.randomUUID().toString();
        String answerMessageId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into conversation(id, workspace_id, title, conversation_type, status)
                values (?, ?, '充分性', 'QA', 'ACTIVE')
                """, conversationId, workspaceId);
        jdbcTemplate.update("""
                insert into conversation_message(
                    id, conversation_id, workspace_id, message_seq, role, content
                ) values (?, ?, ?, 1, 'USER', '问题')
                """, queryMessageId, conversationId, workspaceId);
        jdbcTemplate.update("""
                insert into conversation_message(
                    id, conversation_id, workspace_id, message_seq, role, content
                ) values (?, ?, ?, 2, 'ASSISTANT', '草稿')
                """, answerMessageId, conversationId, workspaceId);

        RetrievalPlan plan = new RetrievalPlan(
                "sufficiency-v1", AnswerMode.QA, List.of(),
                new RetrievalPlan.Budget(3, 1000, 0, 0));
        String historicalRunId = answerRunService.createAndStartRetrieval(
                workspaceId, conversationId, "QA", queryMessageId, plan);
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}", workspaceId, historicalRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence_sufficiency").doesNotExist());
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}/evidence",
                        workspaceId, historicalRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence_sufficiency").doesNotExist());

        String preparedRunId = answerSubmissionService.createPreparedRun(
                workspaceId, conversationId, "QA", queryMessageId, answerMessageId,
                UUID.randomUUID().toString(), "草稿", plan,
                new EvidenceBundle("bundle", "sufficiency-v1", List.of(), false, List.of(), Instant.now()),
                "sufficiency-v1", 1200, "NOT_RUN");
        String stored = jdbcTemplate.queryForObject(
                "select evidence_sufficiency from answer_run where id = ?",
                String.class, preparedRunId);
        assertThat(stored).isEqualTo("NOT_RUN");
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}", workspaceId, preparedRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence_sufficiency").value("NOT_RUN"));
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}/evidence",
                        workspaceId, preparedRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence_sufficiency").value("NOT_RUN"));
    }

    private String createWorkspace() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "充分性工作台",
                                "description", "证据充分性接口"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("workspace_id").asText();
    }
}
