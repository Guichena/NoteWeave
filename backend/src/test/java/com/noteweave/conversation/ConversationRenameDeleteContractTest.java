package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * 验收 11-04 中危 21：会话重命名与删除。
 * 此前 PATCH / DELETE /workspaces/{ws}/conversations/{id} 都是 404。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "test-es"})
@Import(DeterministicEmbeddingTestConfiguration.class)
class ConversationRenameDeleteContractTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void renameChangesTitleAndRejectsBlankTitle() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId, "旧标题");

        mockMvc.perform(patch("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}",
                        workspaceId, conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "  新标题  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversation_id").value(conversationId))
                .andExpect(jsonPath("$.data.title").value("新标题"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/conversations", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("新标题"));

        mockMvc.perform(patch("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}",
                        workspaceId, conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "   "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void renameUnknownConversationReturns404() throws Exception {
        String workspaceId = createWorkspace();
        mockMvc.perform(patch("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}",
                        workspaceId, "missing-conversation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "新标题"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteIsSoftAndHidesConversationEverywhere() throws Exception {
        String workspaceId = createWorkspace();
        String keptId = createConversation(workspaceId, "保留的会话");
        String deletedId = createConversation(workspaceId, "要删除的会话");

        mockMvc.perform(delete("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}",
                        workspaceId, deletedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversation_id").value(deletedId))
                .andExpect(jsonPath("$.data.status").value("DELETED"))
                .andExpect(jsonPath("$.data.deleted").value(true));

        // 软删除：行仍在，只是状态改为 DELETED
        assertThat(jdbcTemplate.queryForObject(
                "select status from conversation where id = ?", String.class, deletedId)).isEqualTo("DELETED");

        MvcResult list = mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/conversations", workspaceId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(list.getResponse().getContentAsString()).path("data");
        assertThat(items.findValuesAsText("conversation_id")).contains(keptId).doesNotContain(deletedId);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages",
                        workspaceId, deletedId))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}",
                        workspaceId, deletedId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "改名"))))
                .andExpect(status().isNotFound());

        // 已删除的会话不能再写入新消息
        MvcResult send = mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", deletedId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "删除后还能发吗",
                                "answer_mode", "QA",
                                "client_request_id", "deleted-conv-" + System.nanoTime()))))
                .andReturn();
        assertThat(send.getResponse().getStatus()).isGreaterThanOrEqualTo(400).isLessThan(500);

        // 重复删除仍返回删除结果，前端重试不会报错
        mockMvc.perform(delete("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}",
                        workspaceId, deletedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void deleteStopsAnswerStillInProgress() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId, "有进行中回答的会话");
        String runId = sendMessage(conversationId);
        // 固定成进行中状态，模拟删除时回答还没有生成完
        jdbcTemplate.update("update answer_run set status = 'GENERATING', finished_at = null where id = ?", runId);

        mockMvc.perform(delete("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}",
                        workspaceId, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelled_answer_run_ids[0]").value(runId));

        assertThat(jdbcTemplate.queryForObject(
                "select status from answer_run where id = ?", String.class, runId)).isEqualTo("CANCELLED");
    }

    @Test
    void deleteUnknownConversationReturns404() throws Exception {
        String workspaceId = createWorkspace();
        mockMvc.perform(delete("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}",
                        workspaceId, "missing-conversation"))
                .andExpect(status().isNotFound());
    }

    private String createWorkspace() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "会话删除重命名工作台"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("workspace_id").asText();
    }

    private String createConversation(String workspaceId, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", title, "conversation_type", "WORKSPACE_CHAT"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("conversation_id").asText();
    }

    private String sendMessage(String conversationId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "删除会话前的问题",
                                "answer_mode", "QA",
                                "client_request_id", "conv-del-" + System.nanoTime()))))
                .andExpect(status().isOk())
                .andReturn();
        String runId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("answer_run_id").asText();
        assertThat(runId).isNotEmpty();
        return runId;
    }
}
