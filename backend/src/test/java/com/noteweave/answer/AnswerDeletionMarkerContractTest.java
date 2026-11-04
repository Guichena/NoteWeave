package com.noteweave.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.support.DeterministicEmbeddingTestConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 验收 11-04 中危 22、23、24：
 * 删除的消息显示此消息已删除，而不是引用的资料已撤销；
 * 回答运行带显式的 deleted / cancelled 标记；
 * 对已删除或已停止的回答提交反馈时返回明确原因，不再是反馈目标已变化。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "test-es"})
@Import(DeterministicEmbeddingTestConfiguration.class)
class AnswerDeletionMarkerContractTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void deletedAnswerShowsDeletedTextAndCarriesDeletedMarker() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        String runId = sendMessage(conversationId);
        String answerMessageId = completeRun(runId, "可以删除的回答正文");

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}", workspaceId, runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(false))
                .andExpect(jsonPath("$.data.cancelled").value(false));

        mockMvc.perform(delete("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages/{messageId}",
                        workspaceId, conversationId, answerMessageId))
                .andExpect(status().isOk());

        JsonNode answer = findMessage(workspaceId, conversationId, answerMessageId);
        assertThat(answer.path("content").asText()).isEqualTo("此消息已删除");
        assertThat(answer.path("context_status").asText()).isEqualTo("DELETED");
        assertThat(answer.path("citations").size()).isZero();

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}", workspaceId, runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.content").value(""));

        // DELETE 回答运行对已删除的回答同样给出删除标记
        mockMvc.perform(delete("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}", workspaceId, runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}/feedback", workspaceId, runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"POSITIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_FEEDBACK_TARGET_DELETED"));
    }

    @Test
    void deletedQuestionShowsDeletedTextInsteadOfSourceRevoked() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        String runId = sendMessage(conversationId);
        completeRun(runId, "回答正文");
        String queryMessageId = jdbcTemplate.queryForObject(
                "select query_message_id from answer_run where id = ?", String.class, runId);
        String answerMessageId = jdbcTemplate.queryForObject(
                "select answer_message_id from answer_run where id = ?", String.class, runId);

        mockMvc.perform(delete("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages/{messageId}",
                        workspaceId, conversationId, queryMessageId))
                .andExpect(status().isOk());

        assertThat(findMessage(workspaceId, conversationId, queryMessageId).path("content").asText())
                .isEqualTo("此消息已删除");
        assertThat(findMessage(workspaceId, conversationId, answerMessageId).path("content").asText())
                .as("提问被删除时不能把回答误报成资料撤销")
                .doesNotContain("资料已撤销");
    }

    @Test
    void stoppedAnswerIsMarkedCancelledAndFeedbackExplainsWhy() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        String runId = sendMessage(conversationId);
        jdbcTemplate.update("update answer_run set status = 'GENERATING', finished_at = null where id = ?", runId);

        mockMvc.perform(delete("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}", workspaceId, runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelled").value(true))
                .andExpect(jsonPath("$.data.deleted").value(false));

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}/feedback", workspaceId, runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"NEGATIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_FEEDBACK_ANSWER_CANCELLED"))
                .andExpect(jsonPath("$.message").value(
                        "这条回答已停止生成，没有可评价的内容；可以重新生成后再评价"));
    }

    @Test
    void stoppingFinishedAnswerTellsUserToDeleteTheMessageInstead() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        String runId = sendMessage(conversationId);
        completeRun(runId, "已经完成的回答");

        MvcResult result = mockMvc.perform(
                        delete("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}", workspaceId, runId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_RUN_TERMINAL"))
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("message").asText())
                .contains("删除对应的消息");
    }

    private String completeRun(String runId, String content) {
        String answerMessageId = jdbcTemplate.queryForObject(
                "select answer_message_id from answer_run where id = ?", String.class, runId);
        jdbcTemplate.update("""
                update answer_run set status = 'COMPLETED', error_code = null, error_message = null,
                    finished_at = current_timestamp
                where id = ?
                """, runId);
        jdbcTemplate.update("""
                update message_revision set content = ?, status = 'FINAL'
                where answer_run_id = ? and revision_no = 1
                """, content, runId);
        jdbcTemplate.update("""
                update conversation_message set content = ?, context_status = 'READY' where id = ?
                """, content, answerMessageId);
        return answerMessageId;
    }

    private JsonNode findMessage(String workspaceId, String conversationId, String messageId) throws Exception {
        MvcResult result = mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages",
                        workspaceId, conversationId))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode message : objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data")) {
            if (messageId.equals(message.path("message_id").asText())) {
                return message;
            }
        }
        throw new AssertionError("消息列表里没有 " + messageId);
    }

    private String createWorkspace() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "回答删除标记工作台"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data").path("workspace_id").asText();
    }

    private String createConversation(String workspaceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "回答删除标记会话", "conversation_type", "WORKSPACE_CHAT"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data").path("conversation_id").asText();
    }

    private String sendMessage(String conversationId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "删除标记测试问题",
                                "answer_mode", "QA",
                                "client_request_id", "del-marker-" + System.nanoTime()))))
                .andExpect(status().isOk())
                .andReturn();
        String runId = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data").path("answer_run_id").asText();
        assertThat(runId).isNotEmpty();
        return runId;
    }
}
