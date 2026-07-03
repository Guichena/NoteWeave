package com.noteweave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Marginalia 式结构化检索漏斗")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 候选资料")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 关系扩展")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 验证批次")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 摘录证据")))
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

        JsonNode secondNoteMessage = sendMessage(conversationId, "NOTE", "继续整理 Note 链路和 Marginalia 的关系");
        String secondNoteRequestId = secondNoteMessage.path("data").path("assistant_request_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", secondNoteRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## Journal 信号")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("历史 Note")));

        JsonNode wikiItem = createWikiPage(workspaceId, noteAssistantMessageId);
        String wikiItemId = wikiItem.path("data").path("item_id").asText();

        mockMvc.perform(post("/api/v2/knowledge-items/{itemId}/versions", wikiItemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "# NoteWeave 阶段3总览\n\n阶段3最终采用 WebKonra / WeKnora 式全量 Wiki 检索链路，并把 [[Note 链路]] 作为资料级检索入口。新增内容会进入第二版。",
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

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}", wikiItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_type").value("WIKI"))
                .andExpect(jsonPath("$.data.latest_version_no").value(2))
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("全量 Wiki 检索链路")))
                .andExpect(jsonPath("$.data.citations[0].quote_text").isNotEmpty());

        JsonNode wikiMessage = sendMessage(conversationId, "WIKI", "NoteWeave 阶段3怎么设计？");
        String wikiRequestId = wikiMessage.path("data").path("assistant_request_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", wikiRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 相关 Wiki 页面")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 页面关系")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("v2")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/wiki")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));
    }

    @Test
    void noteModeShouldExpandCandidateSourcesByTagOverlapRelationSignals() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId, "anchor-research.md", """
                phase3 marginalia shared-signal
                AnchorAlpha 是资料级候选定位的关键样例。
                Note 链路需要先命中这份资料，再通过共享标签扩展相邻资料。
                """);
        uploadSource(workspaceId, "companion.md", """
                phase3 marginalia shared-signal
                这份资料只描述相邻主题材料，它和 anchor-research 共享同一组主题标签。
                它应该通过 relation_hint_expand 进入候选资料，而不是靠 chunk top-k 直接命中。
                """);
        String conversationId = createConversation(workspaceId);

        JsonNode noteMessage = sendMessage(conversationId, "NOTE", "请解释 AnchorAlpha");
        String noteRequestId = noteMessage.path("data").path("assistant_request_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", noteRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("anchor-research.md")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("companion.md")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("relation-expansion")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("verify_batch_sources")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 摘录证据")));
    }

    @Test
    void wikiModeShouldNotFallbackToOrdinaryRagWhenNoWikiPageExists() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId);
        String conversationId = createConversation(workspaceId);

        JsonNode wikiMessage = sendMessage(conversationId, "WIKI", "NoteWeave 阶段3怎么设计？");
        String wikiRequestId = wikiMessage.path("data").path("assistant_request_id").asText();
        String assistantMessageId = wikiMessage.path("data").path("assistant_message_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", wikiRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("当前 Wiki 知识网络还没有可直接命中的正式页面")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("创建 Wiki 页面")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("event: chat.citation"))));

        Integer citationCount = jdbcTemplate.queryForObject(
                "select count(*) from message_citation where message_id = ?",
                Integer.class,
                assistantMessageId
        );
        assertThat(citationCount).isZero();
    }

    @Test
    void wikiPageCanBeCreatedAsWorkspaceAssetWithoutBindingConversationMessage() throws Exception {
        String workspaceId = createWorkspace();

        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "工作台级 Wiki 页面",
                                "content", "# 工作台级 Wiki 页面\n\n这个页面属于研究工作台，不默认绑定某一次聊天。"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_type").value("WIKI"))
                .andReturn();

        String itemId = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("item_id").asText();
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select v.source_message_id
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.id = ?
                """, itemId);
        assertThat(row.get("source_message_id")).isNull();
    }

    @Test
    void wikiIngestShouldBeControlledByWorkspaceLevelSwitch() throws Exception {
        String disabledWorkspaceId = createWorkspace();
        uploadSource(disabledWorkspaceId);
        Integer disabledTaskCount = jdbcTemplate.queryForObject(
                "select count(*) from task where workspace_id = ? and task_type = 'WIKI_INGEST'",
                Integer.class,
                disabledWorkspaceId
        );
        assertThat(disabledTaskCount).isZero();

        String enabledWorkspaceId = createWorkspace();
        mockMvc.perform(put("/api/v2/workspaces/{workspaceId}/wiki-settings", enabledWorkspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("wiki_enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wiki_enabled").value(true));
        uploadSource(enabledWorkspaceId);

        Integer enabledTaskCount = jdbcTemplate.queryForObject(
                "select count(*) from task where workspace_id = ? and task_type = 'WIKI_INGEST'",
                Integer.class,
                enabledWorkspaceId
        );
        Integer outboxCount = jdbcTemplate.queryForObject("""
                select count(*)
                from task_outbox o
                join task t on t.id = o.task_id
                where t.workspace_id = ? and o.topic = 'noteweave.wiki.ingest'
                """, Integer.class, enabledWorkspaceId);
        assertThat(enabledTaskCount).isEqualTo(1);
        assertThat(outboxCount).isEqualTo(1);

        Integer generatedWikiPages = jdbcTemplate.queryForObject(
                "select count(*) from knowledge_item where workspace_id = ? and item_type = 'WIKI'",
                Integer.class,
                enabledWorkspaceId
        );
        assertThat(generatedWikiPages).isEqualTo(1);
    }

    @Test
    void enablingWikiShouldBackfillExistingReadySourcesAndManualRebuildShouldAppendVersions() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId);

        Integer wikiPagesBeforeEnable = jdbcTemplate.queryForObject(
                "select count(*) from knowledge_item where workspace_id = ? and item_type = 'WIKI'",
                Integer.class,
                workspaceId
        );
        assertThat(wikiPagesBeforeEnable).isZero();

        mockMvc.perform(put("/api/v2/workspaces/{workspaceId}/wiki-settings", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("wiki_enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wiki_enabled").value(true));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-home", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pages[0].title").value("phase3.md"))
                .andExpect(jsonPath("$.data.pages[0].latest_version_no").value(1))
                .andExpect(jsonPath("$.data.links").isNotEmpty());

        Integer backfillTaskCount = jdbcTemplate.queryForObject(
                "select count(*) from task where workspace_id = ? and task_type = 'WIKI_INGEST'",
                Integer.class,
                workspaceId
        );
        assertThat(backfillTaskCount).isEqualTo(1);

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/wiki/rebuild", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source_count").value(1))
                .andExpect(jsonPath("$.data.task_count").value(1));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-home", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pages[0].title").value("phase3.md"))
                .andExpect(jsonPath("$.data.pages[0].latest_version_no").value(2));
    }

    @Test
    void deletingSourceShouldRetractGeneratedWikiPageWhenWikiIsEnabled() throws Exception {
        String workspaceId = createWorkspace();
        mockMvc.perform(put("/api/v2/workspaces/{workspaceId}/wiki-settings", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("wiki_enabled", true))))
                .andExpect(status().isOk());

        String sourceId = uploadSource(workspaceId);
        String conversationId = createConversation(workspaceId);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/sources", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source_id").value(sourceId));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-home", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pages[0].title").value("phase3.md"));

        mockMvc.perform(delete("/api/v2/workspaces/{workspaceId}/sources/{sourceId}", workspaceId, sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELETED"))
                .andExpect(jsonPath("$.data.wiki_retract_task_id").isNotEmpty());

        Integer retractTaskCount = jdbcTemplate.queryForObject(
                "select count(*) from task where workspace_id = ? and task_type = 'WIKI_RETRACT'",
                Integer.class,
                workspaceId
        );
        assertThat(retractTaskCount).isEqualTo(1);

        Map<String, Object> sourceRow = jdbcTemplate.queryForMap("select status, index_status from source where id = ?", sourceId);
        assertThat(sourceRow.get("status")).isEqualTo("DELETED");
        assertThat(sourceRow.get("index_status")).isEqualTo("DELETED");

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/sources", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-home", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pages").isEmpty());

        JsonNode wikiMessage = sendMessage(conversationId, "WIKI", "NoteWeave 阶段3怎么设计？");
        String wikiRequestId = wikiMessage.path("data").path("assistant_request_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", wikiRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("当前 Wiki 知识网络还没有可直接命中的正式页面")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("event: chat.citation"))));
    }

    @Test
    void wikiManagementEndpointsShouldCoverSearchGraphStatsLintAndAutoFix() throws Exception {
        String workspaceId = createWorkspace();
        MvcResult create = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "Wiki 管理页",
                                "content", "# Wiki 管理页\n\n这里引用 [[缺失页面]] 来测试断链修复。"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String itemId = objectMapper.readTree(create.getResponse().getContentAsString()).path("data").path("item_id").asText();

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-search", workspaceId)
                        .param("q", "管理"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("Wiki 管理页"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-graph", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].item_id").value(itemId))
                .andExpect(jsonPath("$.data.edges[0].target_title").value("缺失页面"))
                .andExpect(jsonPath("$.data.edges[0].relation_status").value("UNRESOLVED"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-stats", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page_count").value(1))
                .andExpect(jsonPath("$.data.unresolved_link_count").value(1));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-issues", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].issue_type").value("BROKEN_LINK"));

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/wiki/auto-fix", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created_pages").value(1));

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/wiki/rebuild-links", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolved_link_count").value(1));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-log", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].event_type").isNotEmpty());

        MvcResult missingPage = mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-search", workspaceId)
                        .param("q", "缺失页面"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("缺失页面"))
                .andReturn();
        String missingPageId = objectMapper.readTree(missingPage.getResponse().getContentAsString()).path("data").path(0).path("item_id").asText();

        mockMvc.perform(patch("/api/v2/knowledge-items/{itemId}/title", missingPageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "已补齐页面"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("已补齐页面"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-graph", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.edges[0].target_title").value("已补齐页面"))
                .andExpect(jsonPath("$.data.edges[0].relation_status").value("RESOLVED"));

        mockMvc.perform(delete("/api/v2/knowledge-items/{itemId}", missingPageId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-search", workspaceId)
                        .param("q", "已补齐页面"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-log", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].event_type").value("DELETE_PAGE"));
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
                                "content", "# NoteWeave 阶段3总览\n\n阶段3采用全量 Wiki 检索逻辑，并保留 [[Note 链路]] 作为相关页面。",
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

    private String uploadSource(String workspaceId) throws Exception {
        return uploadSource(workspaceId, "phase3.md", """
                NoteWeave phase3 补齐三种聊天链路中的 Note 和 Wiki。
                Note 链路参考 Marginalia，先定位候选资料，再打开原文窗口，生成摘录证据和带引用回答。
                Wiki 链路参考 WebKonra / WeKnora，优先读取已经沉淀的 Wiki 页面、索引、页面链接和来源回链。
                """);
    }

    private String uploadSource(String workspaceId, String fileName, String markdown) throws Exception {
        byte[] content = """
                %s
                """.formatted(markdown).getBytes(StandardCharsets.UTF_8);

        MvcResult init = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/uploads", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "file_name", fileName,
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
        return jdbcTemplate.queryForObject(
                "select source_id from document_upload where id = ?",
                String.class,
                uploadId
        );
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
