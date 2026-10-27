package com.noteweave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatAnswerGenerationAdapter;
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
 * NR02：删除后的内容保护。删除回答消息后，运行详情不得再返回 message_revision 正文；
 * 删除问题消息后运行详情不得再返回由问题派生的检索改写视图；
 * 生成完成的晚到 persistContent 不得把已删除消息的正文复活。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "test-es"})
@Import(DeterministicEmbeddingTestConfiguration.class)
class DeletedAnswerContentProtectionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ChatAnswerGenerationAdapter generationAdapter;

    @Test
    void deletedAnswerMessageHidesRevisionContentAndBlocksLatePersist() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        String answerRunId = sendMessage(workspaceId, conversationId);
        Map<String, Object> run = jdbcTemplate.queryForMap(
                "select query_message_id, answer_message_id from answer_run where id = ?", answerRunId);
        String answerMessageId = String.valueOf(run.get("answer_message_id"));
        String queryMessageId = String.valueOf(run.get("query_message_id"));

        // 固化为确定终态：正文、修订与改写计划均为已知值
        jdbcTemplate.update("""
                update message_revision set content = '机密答案正文NR02', status = 'FINAL'
                where answer_run_id = ? and revision_no = 1
                """, answerRunId);
        jdbcTemplate.update("""
                update conversation_message set content = '机密答案正文NR02', context_status = 'READY'
                where id = ?
                """, answerMessageId);
        jdbcTemplate.update("""
                update answer_run set retrieval_plan_json = ?
                where id = ?
                """, """
                {"steps":[{"filters":{"rewrite_is_standalone":true,"standalone_query":"原始问题NR02",
                "keyword_query":"NR02","rewrite_method":"RULE"}}]}
                """, answerRunId);

        // 基线：删除前正文与改写视图可见
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}", workspaceId, answerRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("机密答案正文NR02"))
                .andExpect(jsonPath("$.data.retrieval_rewrite.standalone_query").value("原始问题NR02"));

        // 删除回答消息：运行详情正文清空（revision 行保留为审计，但不再对外服务）
        mockMvc.perform(delete("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages/{messageId}",
                        workspaceId, conversationId, answerMessageId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}", workspaceId, answerRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(""))
                .andExpect(jsonPath("$.data.retrieval_rewrite.standalone_query").value("原始问题NR02"));

        // 晚到的生成完成写回不得复活正文
        generationAdapter.persistContent(workspaceId, answerMessageId, "晚到结果不应写入");
        assertThat(jdbcTemplate.queryForObject(
                "select content from conversation_message where id = ?", String.class, answerMessageId))
                .isEmpty();

        // 删除问题消息：由问题派生的改写视图一并隐藏
        mockMvc.perform(delete("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages/{messageId}",
                        workspaceId, conversationId, queryMessageId))
                .andExpect(status().isOk());

        MvcResult detail = mockMvc.perform(
                        get("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}", workspaceId, answerRunId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(detail.getResponse().getContentAsString()).path("data");
        String questionState = jdbcTemplate.queryForMap("""
                select r.query_message_id, m.context_status
                from answer_run r left join conversation_message m on m.id = r.query_message_id
                where r.id = ?
                """, answerRunId).toString();
        assertThat(data.path("retrieval_rewrite").isMissingNode()
                || data.path("retrieval_rewrite").isNull())
                .as("删除问题消息后改写视图应隐藏；问题消息状态: %s；响应: %s",
                        questionState, detail.getResponse().getContentAsString())
                .isTrue();
        assertThat(data.path("content").asText()).isEmpty();
    }

    /**
     * CHAT-01：生成完成写回正文时必须同步 content_hash。
     * 此前只改正文，消息带着草稿的哈希进入上下文投影与摘要，正文与哈希从此对不上。
     */
    @Test
    void persistContentKeepsContentAndHashInStep() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        String answerRunId = sendMessage(workspaceId, conversationId);
        String answerMessageId = String.valueOf(jdbcTemplate.queryForMap(
                "select answer_message_id from answer_run where id = ?", answerRunId).get("answer_message_id"));

        // 起点：草稿正文与草稿哈希（正文与哈希此时一致）
        jdbcTemplate.update("""
                update conversation_message set content = '草稿正文CHAT01', content_hash = ?,
                    context_status = 'PENDING'
                where id = ?
                """, sha256("草稿正文CHAT01"), answerMessageId);

        generationAdapter.persistContent(workspaceId, answerMessageId, "润色后的最终正文CHAT01");

        String content = jdbcTemplate.queryForObject(
                "select content from conversation_message where id = ?", String.class, answerMessageId);
        String hash = jdbcTemplate.queryForObject(
                "select content_hash from conversation_message where id = ?", String.class, answerMessageId);
        assertThat(content).isEqualTo("润色后的最终正文CHAT01");
        assertThat(hash)
                .as("写回正文后哈希必须跟着更新，不能还是草稿的哈希")
                .isEqualTo(sha256("润色后的最终正文CHAT01"))
                .isNotEqualTo(sha256("草稿正文CHAT01"));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String createWorkspace() throws Exception {        MvcResult result = mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "NR02内容保护工作台"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("workspace_id").asText();
    }

    private String createConversation(String workspaceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "NR02会话", "conversation_type", "WORKSPACE_CHAT"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("conversation_id").asText();
    }

    private String sendMessage(String workspaceId, String conversationId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "NR02测试问题",
                                "answer_mode", "QA",
                                "client_request_id", "nr02-req-" + System.nanoTime()))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        String runId = data.path("answer_run_id").asText();
        assertThat(runId).as("提交响应应包含 answer_run_id，实际响应: %s",
                result.getResponse().getContentAsString()).isNotEmpty();
        // 消息投递本身不要求流完成——run 行与 revision 已在提交路径创建/将由固定化 SQL 固化
        return runId;
    }
}
