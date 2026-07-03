package com.noteweave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
class Phase3NoteWikiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void noteAndWikiModesShouldUseDifferentKnowledgeChains() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId);
        assertSourceMetadataCreated(workspaceId);
        String conversationId = createConversation(workspaceId);

        JsonNode noteMessage = sendMessage(conversationId, "NOTE", "请整理 NoteWeave 阶段3的 Note 链路");
        String noteAssistantMessageId = noteMessage.path("data").path("assistant_message_id").asText();
        String noteRequestId = noteMessage.path("data").path("assistant_request_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", noteRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Marginalia 式结构化阅读漏斗")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 候选资料")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 摘录卡片")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        mockMvc.perform(post("/api/v2/messages/{messageId}/save-as-note", noteAssistantMessageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "阶段3 Note 链路整理"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_type").value("NOTE"))
                .andExpect(jsonPath("$.data.latest_version_no").value(1));

        Integer noteCount = jdbcTemplate.queryForObject(
                "select count(*) from knowledge_item where workspace_id = ? and item_type = 'NOTE'",
                Integer.class,
                workspaceId
        );
        assertThat(noteCount).isEqualTo(1);

        JsonNode wikiItem = createWikiPage(workspaceId, noteAssistantMessageId);
        String wikiItemId = wikiItem.path("data").path("item_id").asText();

        mockMvc.perform(post("/api/v2/knowledge-items/{itemId}/versions", wikiItemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "# NoteWeave 阶段3总览\n\n阶段3最终采用 WeKnora 式 Wiki-first 页面链路，并把 [[Note 链路]] 作为结构化阅读入口。新增内容会进入第二版。",
                                "source_message_id", noteAssistantMessageId
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_type").value("WIKI"))
                .andExpect(jsonPath("$.data.latest_version_no").value(2));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-home", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wiki_url").value("/workspaces/" + workspaceId + "/wiki"))
                .andExpect(jsonPath("$.data.pages[0].title").value("NoteWeave 阶段3总览"))
                .andExpect(jsonPath("$.data.pages[0].latest_version_no").value(2))
                .andExpect(jsonPath("$.data.links[0].target_title").value("Note 链路"));

        JsonNode wikiMessage = sendMessage(conversationId, "WIKI", "NoteWeave 阶段3怎么设计？");
        String wikiRequestId = wikiMessage.path("data").path("assistant_request_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", wikiRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 相关 Wiki 页面")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("v2")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/wiki")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));
    }

    private void assertSourceMetadataCreated(String workspaceId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select summary, tags_json, metadata_json from source where workspace_id = ?
                """, workspaceId);
        assertThat((String) row.get("summary")).contains("Marginalia");
        assertThat((String) row.get("tags_json")).contains("phase3");
        assertThat((String) row.get("metadata_json")).contains("marginalia_structured_reading_funnel");
    }

    private JsonNode createWikiPage(String workspaceId, String noteAssistantMessageId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "NoteWeave 阶段3总览",
                                "content", "# NoteWeave 阶段3总览\n\n阶段3采用 Wiki-first 回答逻辑，并保留 [[Note 链路]] 作为相关页面。",
                                "source_message_id", noteAssistantMessageId
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_type").value("WIKI"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String createWorkspace() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "阶段3工作台",
                                "description", "用于 Note/Wiki 链路测试"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("workspace_id").asText();
    }

    private void uploadSource(String workspaceId) throws Exception {
        byte[] content = """
                NoteWeave phase3 补齐三种聊天链路中的 Note 和 Wiki。
                Note 链路参考 Marginalia，先定位候选资料，再打开原文窗口，生成摘录卡片和结构化笔记。
                Wiki 链路参考 WeKnora，优先读取已经沉淀的 Wiki 页面，并提供默认 Wiki 工作台入口。
                """.getBytes(StandardCharsets.UTF_8);

        MvcResult init = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/uploads", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "file_name", "phase3.md",
                                "file_size", content.length,
                                "mime_type", "text/markdown",
                                "chunk_size", content.length,
                                "total_chunks", 1
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String uploadId = objectMapper.readTree(init.getResponse().getContentAsString()).path("data").path("upload_id").asText();

        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, 0)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(content))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v2/uploads/{uploadId}/complete", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.index_status").value("INDEXED"));
    }

    private String createConversation(String workspaceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "阶段3聊天",
                                "conversation_type", "WORKSPACE_CHAT"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("conversation_id").asText();
    }

    private JsonNode sendMessage(String conversationId, String answerMode, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", content,
                                "answer_mode", answerMode,
                                "client_request_id", answerMode + "-" + System.nanoTime()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistant_message_id").isNotEmpty())
                .andExpect(jsonPath("$.data.assistant_request_id").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
