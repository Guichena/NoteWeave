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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 资料定位")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("【候选资料】")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("【关系扩展】")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("【验证摘要】")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 深读窗口")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 摘录证据")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("## 继续追问"))))
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("【Journal 信号】")))
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
                .andExpect(jsonPath("$.data.page_kind").isNotEmpty())
                .andExpect(jsonPath("$.data.latest_version_no").value(2))
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("全量 Wiki 检索链路")))
                .andExpect(jsonPath("$.data.source_message_id").value(noteAssistantMessageId))
                .andExpect(jsonPath("$.data.version_created_at").isNotEmpty())
                .andExpect(jsonPath("$.data.citations[0].quote_text").isNotEmpty())
                .andExpect(jsonPath("$.data.outgoing_links[0].target_title").value("Note 链路"))
                .andExpect(jsonPath("$.data.backlinks").isArray());

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}/versions", wikiItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version_no").value(2))
                .andExpect(jsonPath("$.data[1].version_no").value(1))
                .andExpect(jsonPath("$.data[0].citation_count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}/versions/{versionNo}", wikiItemId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version_no").value(1))
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("NoteWeave")))
                .andExpect(jsonPath("$.data.source_message_id").value(noteAssistantMessageId))
                .andExpect(jsonPath("$.data.created_at").isNotEmpty())
                .andExpect(jsonPath("$.data.citations[0].quote_text").isNotEmpty());

        JsonNode wikiMessage = sendMessage(conversationId, "WIKI", "NoteWeave 阶段3怎么设计？");
        String wikiRequestId = wikiMessage.path("data").path("assistant_request_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", wikiRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 相关 Wiki 页面")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 综合结论")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 关键页面关系")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 反向引用关系")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 来源回链")))
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("candidate_quota_trace:")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("verify_admission_trace:")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("related_entries")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("window_locators")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("read_objective=")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("selection_reason=")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("verify_admission_reason=")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("parse=PARSED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("index=INDEXED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("heading=")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 摘录证据")));
    }

    @Test
    void noteModeShouldPrioritizeMetadataCoverageAndExplainMatchedFields() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId, "AnchorCoverage.md", """
                Spec2026 architecture review.
                这份资料把 AnchorCoverage 放在标题位，把 Spec2026 放在摘要与结构化元数据位，
                应该在资料级 metadata recall 中优先于只在正文窗口里重复关键词的资料。
                """);
        uploadSource(workspaceId, "sample-window.md", """
                AnchorCoverage Spec2026 AnchorCoverage Spec2026
                这份资料的标题 metadata 很弱，两个关键词主要出现在正文样本文本里。
                """);
        String conversationId = createConversation(workspaceId);

        JsonNode noteMessage = sendMessage(conversationId, "NOTE", "AnchorCoverage Spec2026");
        String noteRequestId = noteMessage.path("data").path("assistant_request_id").asText();

        MvcResult result = mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", noteRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("query_coverage=2/2")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("matched_fields=")))
                .andReturn();

        String stream = result.getResponse().getContentAsString();
        assertThat(stream).contains("matched_fields=title");
        assertThat(stream.indexOf("AnchorCoverage.md")).isLessThan(stream.indexOf("sample-window.md"));
    }

    @Test
    void noteModeShouldKeepSourceTypeDiversityInCandidateAndVerifyBatch() throws Exception {
        String workspaceId = createWorkspace();
        String markdownA = uploadSource(workspaceId, "quota-main-a.md", """
                QuotaDiversityToken SpecGrid
                这份 MARKDOWN 资料提供主干解释，会在 metadata recall 中获得较高分。
                """);
        String markdownB = uploadSource(workspaceId, "quota-main-b.md", """
                QuotaDiversityToken SpecGrid
                第二份 MARKDOWN 资料继续补充同一主题，用来制造同类型资料挤满候选的场景。
                """);
        String markdownC = uploadSource(workspaceId, "quota-main-c.md", """
                QuotaDiversityToken SpecGrid
                第三份 MARKDOWN 资料继续围绕同一主题展开，默认情况下也很容易被一起选中。
                """);
        String sheetSource = uploadSource(workspaceId, "quota-sheet.md", """
                QuotaDiversityToken
                这份资料代表表格型来源，虽然正文更短，但在多形态工作台里仍应进入候选与验证批次。
                """);
        jdbcTemplate.update("update source set source_type = 'MARKDOWN' where id in (?, ?, ?)", markdownA, markdownB, markdownC);
        jdbcTemplate.update("update source set source_type = 'SPREADSHEET' where id = ?", sheetSource);

        String conversationId = createConversation(workspaceId);
        JsonNode noteMessage = sendMessage(conversationId, "NOTE", "请整理 QuotaDiversityToken SpecGrid");
        String noteRequestId = noteMessage.path("data").path("assistant_request_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", noteRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("source-type-quota")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("quota-sheet.md")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SPREADSHEET")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("candidate:source-type-quota")));
    }

    @Test
    void noteModeShouldUseRealWindowReadinessInsteadOfSampleTextPresence() throws Exception {
        String workspaceId = createWorkspace();
        String windowlessSource = uploadSource(workspaceId, "windowless-top.md", """
                WindowReadyToken SpecReadiness
                这份资料在 metadata 上非常强，但随后会被人为删除 source_window，
                用来验证 Note 链路不会再把 sample_text 误判成 source-window-ready。
                """);
        uploadSource(workspaceId, "window-ready.md", """
                WindowReadyToken SpecReadiness
                这份资料保留正常的 source_window，可用于真正的原文深读。
                """);
        jdbcTemplate.update("""
                delete from source_window
                where source_chunk_id in (
                    select id from source_chunk where workspace_id = ? and source_id = ?
                )
                """, workspaceId, windowlessSource);

        String conversationId = createConversation(workspaceId);
        JsonNode noteMessage = sendMessage(conversationId, "NOTE", "请整理 WindowReadyToken SpecReadiness");
        String noteRequestId = noteMessage.path("data").path("assistant_request_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", noteRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("windowless-top.md")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("source-window-missing")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("window-ready.md")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("source-window-ready")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("readiness=")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("window-fallback")));
    }

    @Test
    void noteModeShouldDiscoverTwoHopNeighborByRelationGraph() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId, "anchor-graph.md", """
                AnchorAlpha bridgeAB bridgeAB
                这份资料只直接命中 AnchorAlpha，用作本轮 anchor 资料。
                """);
        uploadSource(workspaceId, "bridge-node.md", """
                bridgeAB bridgeAB bridgeBC bridgeBC
                这份资料和 anchor 共享 bridgeAB，也和深层邻居共享 bridgeBC。
                """);
        uploadSource(workspaceId, "deep-neighbor.md", """
                bridgeBC bridgeBC graphNeighborProof
                这份资料不直接命中 AnchorAlpha，但应该通过图传播进入 relation expansion。
                """);
        String conversationId = createConversation(workspaceId);

        JsonNode noteMessage = sendMessage(conversationId, "NOTE", "请解释 AnchorAlpha");
        String noteRequestId = noteMessage.path("data").path("assistant_request_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", noteRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("deep-neighbor.md")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("graph-neighbor")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("relation-expansion")));
    }

    @Test
    void noteModeShouldSurfaceTurnCitationNeighborAndReadWindowRoles() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId, "anchor-turn.md", """
                AnchorTurnShared
                AnchorTurnShared
                AnchorTurnShared

                这份资料用于当前问题的主命中资料，会触发主窗口读取。

                AdditionalAnchorCue
                AnchorTurnShared repeat segment 01 for long reading window planning.
                AnchorTurnShared repeat segment 02 for long reading window planning.
                AnchorTurnShared repeat segment 03 for long reading window planning.
                AnchorTurnShared repeat segment 04 for long reading window planning.
                AnchorTurnShared repeat segment 05 for long reading window planning.
                AnchorTurnShared repeat segment 06 for long reading window planning.
                AnchorTurnShared repeat segment 07 for long reading window planning.
                AnchorTurnShared repeat segment 08 for long reading window planning.
                AnchorTurnShared repeat segment 09 for long reading window planning.
                AnchorTurnShared repeat segment 10 for long reading window planning.
                AnchorTurnShared repeat segment 11 for long reading window planning.
                AnchorTurnShared repeat segment 12 for long reading window planning.
                AnchorTurnShared repeat segment 13 for long reading window planning.
                AnchorTurnShared repeat segment 14 for long reading window planning.
                AnchorTurnShared repeat segment 15 for long reading window planning.
                """);
        uploadSource(workspaceId, "turn-neighbor.md", """
                TurnNeighborOnly
                TurnNeighborOnly
                TurnNeighborOnly

                这份资料不会直接命中 AnchorTurn，但会在一次问答里与 anchor 共同被引用，
                后续应通过 co-cited-turns 进入 related_entries。
                """);
        String conversationId = createConversation(workspaceId);

        JsonNode qaMessage = sendMessage(conversationId, "QA", "请同时总结 AnchorTurnShared 和 TurnNeighborOnly");
        String qaRequestId = qaMessage.path("data").path("assistant_request_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", qaRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        JsonNode noteMessage = sendMessage(conversationId, "NOTE", "请解释 AnchorTurnShared");
        String noteRequestId = noteMessage.path("data").path("assistant_request_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", noteRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("co_cited_turns=")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("co-cited-turns")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("## 继续追问"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("related_entries=")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("window_has_more=true")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("【原文窗口】")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("read_role=primary-window")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("read_objective=best-evidence")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("read_objective=adjacent-context")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("primary-window")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("continuation-window")));
    }

    @Test
    void noteModeShouldDemoteStaleJournalHitsAndShowFreshnessStatus() throws Exception {
        String workspaceId = createWorkspace();
        String staleSourceId = uploadSource(workspaceId, "stale-journal.md", """
                JournalFreshness staleBranchToken
                这份资料会先被保存为 Note，然后再人为更新 source.updated_at，
                用来验证历史 Note 在来源变更后会降级为 stale journal。
                """);
        uploadSource(workspaceId, "fresh-journal.md", """
                JournalFreshness freshBranchToken
                这份资料会保持最新状态，对应的 Note 应该优先于过期 Journal。
                """);
        String conversationId = createConversation(workspaceId);

        JsonNode staleQaMessage = sendMessage(conversationId, "QA", "请总结 staleBranchToken");
        String staleAssistantMessageId = staleQaMessage.path("data").path("assistant_message_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", staleQaMessage.path("data").path("assistant_request_id").asText()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        mockMvc.perform(post("/api/v2/messages/{messageId}/save-as-note", staleAssistantMessageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "A过期资料整理"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_type").value("NOTE"));

        JsonNode freshQaMessage = sendMessage(conversationId, "QA", "请总结 freshBranchToken");
        String freshAssistantMessageId = freshQaMessage.path("data").path("assistant_message_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", freshQaMessage.path("data").path("assistant_request_id").asText()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        mockMvc.perform(post("/api/v2/messages/{messageId}/save-as-note", freshAssistantMessageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Z新鲜资料整理"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_type").value("NOTE"));

        jdbcTemplate.update("""
                update source
                set updated_at = dateadd('SECOND', 5, current_timestamp)
                where id = ?
                """, staleSourceId);

        JsonNode noteMessage = sendMessage(conversationId, "NOTE", "继续整理 JournalFreshness");
        String noteRequestId = noteMessage.path("data").path("assistant_request_id").asText();

        MvcResult result = mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", noteRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("【Journal 信号】")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("状态 stale-source-updated")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("stale_sources=1")))
                .andReturn();

        String stream = result.getResponse().getContentAsString();
        assertThat(stream.indexOf("Z新鲜资料整理")).isLessThan(stream.indexOf("A过期资料整理"));
    }

    @Test
    void noteModeShouldKeepUnavailableJournalForAuditButExcludeItFromRecallSignals() throws Exception {
        String workspaceId = createWorkspace();
        String deletedSourceId = uploadSource(workspaceId, "deleted-journal.md", """
                AvailabilityShared deletedOnlyToken
                这份资料会先沉淀成历史 Note，然后再被标记为 DELETED。
                """);
        uploadSource(workspaceId, "active-availability.md", """
                AvailabilityShared activeOnlyToken
                这份资料会继续保持 READY，用来承接后续的 Note 回答。
                """);
        String conversationId = createConversation(workspaceId);

        JsonNode qaMessage = sendMessage(conversationId, "QA", "请总结 deletedOnlyToken");
        String assistantMessageId = qaMessage.path("data").path("assistant_message_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", qaMessage.path("data").path("assistant_request_id").asText()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        mockMvc.perform(post("/api/v2/messages/{messageId}/save-as-note", assistantMessageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "失效来源整理"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_type").value("NOTE"));

        jdbcTemplate.update("""
                update source
                set status = 'DELETED', parse_status = 'DELETED', index_status = 'DELETED', updated_at = current_timestamp
                where id = ?
                """, deletedSourceId);

        JsonNode noteMessage = sendMessage(conversationId, "NOTE", "继续整理 AvailabilityShared");
        String noteRequestId = noteMessage.path("data").path("assistant_request_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", noteRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("【Journal 信号】")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("source-unavailable")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("unavailable_sources=1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("active-availability.md")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("journal-note, source-window-ready"))));
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("开启工作台级 Wiki 构建")))
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
    void manualWikiRepairShouldAppendVersionInsteadOfCreatingDuplicateTitlePage() throws Exception {
        String workspaceId = createWorkspace();

        MvcResult create = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "重复标题页",
                                "content", "# 重复标题页\n\n这是第一版内容。"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latest_version_no").value(1))
                .andReturn();
        String itemId = objectMapper.readTree(create.getResponse().getContentAsString()).path("data").path("item_id").asText();

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "重复标题页",
                                "content", "# 重复标题页\n\n这是第二版修正内容。"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_id").value(itemId))
                .andExpect(jsonPath("$.data.latest_version_no").value(2));

        Integer activePageCount = jdbcTemplate.queryForObject("""
                select count(*)
                from knowledge_item
                where workspace_id = ? and item_type = 'WIKI' and title = '重复标题页' and status = 'ACTIVE'
                """, Integer.class, workspaceId);
        assertThat(activePageCount).isEqualTo(1);

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}/versions", itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version_no").value(2))
                .andExpect(jsonPath("$.data[1].version_no").value(1));
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
        assertThat(generatedWikiPages).isGreaterThanOrEqualTo(2);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-home", enabledWorkspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pages[*].title").value(org.hamcrest.Matchers.hasItem("phase3.md")))
                .andExpect(jsonPath("$.data.pages[*].title").value(org.hamcrest.Matchers.hasItem("Wiki Index")));
    }

    @Test
    void enablingWikiShouldBackfillExistingReadySourcesAndManualRebuildShouldAppendVersions() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki/rebuild-advice", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.should_enable_wiki").value(true))
                .andExpect(jsonPath("$.data.ready_source_count").value(1))
                .andExpect(jsonPath("$.data.recommended_action").value("ENABLE_WIKI"));

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
                .andExpect(jsonPath("$.data.pages[*].title").value(org.hamcrest.Matchers.hasItem("phase3.md")))
                .andExpect(jsonPath("$.data.pages[*].title").value(org.hamcrest.Matchers.hasItem("Wiki Index")))
                .andExpect(jsonPath("$.data.pages[?(@.title=='phase3.md')].latest_version_no").value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data.links").isNotEmpty());

        String wikiIndexItemId = jdbcTemplate.queryForObject("""
                select id
                from knowledge_item
                where workspace_id = ? and item_type = 'WIKI' and title = 'Wiki Index' and status = 'ACTIVE'
                """, String.class, workspaceId);

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}", wikiIndexItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("## 治理概览")))
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("## 页面分布")));

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
                .andExpect(jsonPath("$.data.pages[*].title").value(org.hamcrest.Matchers.hasItem("phase3.md")))
                .andExpect(jsonPath("$.data.pages[*].title").value(org.hamcrest.Matchers.hasItem("Wiki Index")))
                .andExpect(jsonPath("$.data.pages[?(@.title=='phase3.md')].latest_version_no").value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$.data.pages[?(@.title=='Wiki Index')].latest_version_no").value(org.hamcrest.Matchers.hasItem(2)));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-index", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recent_tasks[0].task_type").value("WIKI_INGEST"))
                .andExpect(jsonPath("$.data.recent_tasks[0].task_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.recent_tasks[0].target_type").value("SOURCE"))
                .andExpect(jsonPath("$.data.recent_tasks[0].target_title").value("phase3.md"))
                .andExpect(jsonPath("$.data.recent_tasks[0].related_pages").isNotEmpty())
                .andExpect(jsonPath("$.data.recent_tasks[0].related_pages[0].item_id").isNotEmpty())
                .andExpect(jsonPath("$.data.recent_sources[0].title").value("phase3.md"))
                .andExpect(jsonPath("$.data.recent_sources[0].related_pages").isNotEmpty())
                .andExpect(jsonPath("$.data.recent_sources[0].related_pages[0].title").isNotEmpty())
                .andExpect(jsonPath("$.data.recent_sources[0].recommended_action").value("OPEN_WIKI_PAGE"))
                .andExpect(jsonPath("$.data.recent_sources[0].focus_item_id").isNotEmpty())
                .andExpect(jsonPath("$.data.recent_sources[0].focus_title").isNotEmpty());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-stats", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recent_tasks[0].task_type").value("WIKI_INGEST"))
                .andExpect(jsonPath("$.data.recent_tasks[0].target_title").value("phase3.md"))
                .andExpect(jsonPath("$.data.recent_tasks[0].related_pages").isNotEmpty())
                .andExpect(jsonPath("$.data.recent_tasks[0].related_pages[0].title").isNotEmpty());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki/rebuild-advice", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active_wiki_page_count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("持续演化状态")))
                .andExpect(jsonPath("$.data.recommended_action").value("OPEN_WIKI_HOME"));
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
                .andExpect(jsonPath("$.data.pages[*].title").value(org.hamcrest.Matchers.hasItem("phase3.md")))
                .andExpect(jsonPath("$.data.pages[*].title").value(org.hamcrest.Matchers.hasItem("Wiki Index")));

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

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-home", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pages").isEmpty());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-stats", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recent_tasks[0].task_type").value("WIKI_RETRACT"))
                .andExpect(jsonPath("$.data.recent_tasks[0].target_type").value("SOURCE"))
                .andExpect(jsonPath("$.data.recent_tasks[0].target_title").value("phase3.md"))
                .andExpect(jsonPath("$.data.recent_tasks[0].related_pages").isEmpty());

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
                                "content", "# Wiki 管理页\n\n这里引用 [[缺失页面]] 来测试断链修复。\n\n再次引用 [[缺失页面]]，用于测试 mention_count。"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String itemId = objectMapper.readTree(create.getResponse().getContentAsString()).path("data").path("item_id").asText();

        MvcResult conceptPage = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "Graph Concept Page",
                                "content", "# Graph Concept Page\n\nThis concept page is used to verify graph kind filtering."
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page_kind").value("CONCEPT"))
                .andReturn();
        String conceptItemId = objectMapper.readTree(conceptPage.getResponse().getContentAsString()).path("data").path("item_id").asText();

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-search", workspaceId)
                        .param("q", "管理"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("Wiki 管理页"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-graph", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].item_id").value(itemId))
                .andExpect(jsonPath("$.data.edges[0].target_title").value("缺失页面"))
                .andExpect(jsonPath("$.data.edges[0].relation_status").value("UNRESOLVED"))
                .andExpect(jsonPath("$.data.edges[0].mention_count").value(2))
                .andExpect(jsonPath("$.data.meta.mode").value("overview"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-graph", workspaceId)
                        .param("kinds", "CONCEPT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes.length()").value(1))
                .andExpect(jsonPath("$.data.nodes[0].item_id").value(conceptItemId))
                .andExpect(jsonPath("$.data.nodes[0].page_kind").value("CONCEPT"))
                .andExpect(jsonPath("$.data.meta.total_nodes").value(1));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-stats", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page_count").value(2))
                .andExpect(jsonPath("$.data.unresolved_link_count").value(1))
                .andExpect(jsonPath("$.data.auto_fixable_issue_count").value(1))
                .andExpect(jsonPath("$.data.manual_review_issue_count").value(3))
                .andExpect(jsonPath("$.data.pages_by_kind.TOPIC").value(1))
                .andExpect(jsonPath("$.data.pages_by_kind.CONCEPT").value(1))
                .andExpect(jsonPath("$.data.recent_updates[*].title").value(org.hamcrest.Matchers.hasItem("Graph Concept Page")))
                .andExpect(jsonPath("$.data.recent_tasks").isArray())
                .andExpect(jsonPath("$.data.wiki_enabled").value(false));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-index", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page_count").value(2))
                .andExpect(jsonPath("$.data.source_backed_page_count").value(0))
                .andExpect(jsonPath("$.data.manual_page_count").value(2))
                .andExpect(jsonPath("$.data.auto_fixable_issue_count").value(1))
                .andExpect(jsonPath("$.data.manual_review_issue_count").value(3))
                .andExpect(jsonPath("$.data.top_issues[0].issue_type").value("BROKEN_LINK"))
                .andExpect(jsonPath("$.data.top_issues[0].auto_fixable").value(true))
                .andExpect(jsonPath("$.data.recent_tasks").isArray());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki/rebuild-advice", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.should_enable_wiki").value(false))
                .andExpect(jsonPath("$.data.active_wiki_page_count").value(2))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("断链")))
                .andExpect(jsonPath("$.data.recommended_action").value("AUTO_FIX_WIKI"))
                .andExpect(jsonPath("$.data.recommended_issue_type").value("BROKEN_LINK"))
                .andExpect(jsonPath("$.data.focus_item_id").value(itemId))
                .andExpect(jsonPath("$.data.focus_title").isNotEmpty());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-issues", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].issue_type").value("BROKEN_LINK"))
                .andExpect(jsonPath("$.data[0].auto_fixable").value(true))
                .andExpect(jsonPath("$.data[0].action_code").value("PREFILL_MISSING_PAGE"))
                .andExpect(jsonPath("$.data[1].auto_fixable").value(false));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-issues", workspaceId)
                        .param("auto_fixable", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].issue_type").value("BROKEN_LINK"))
                .andExpect(jsonPath("$.data[0].action_code").value("PREFILL_MISSING_PAGE"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-issues", workspaceId)
                        .param("severity", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.data[0].issue_type").value("BROKEN_LINK"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-issues", workspaceId)
                        .param("item_id", itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].item_id").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(itemId))));

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/wiki/auto-fix", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created_pages").value(1));

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/wiki/rebuild-links", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolved_link_count").value(1));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-graph", workspaceId)
                        .param("mode", "ego")
                        .param("center", itemId)
                        .param("depth", "1")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meta.mode").value("ego"))
                .andExpect(jsonPath("$.data.meta.center_item_id").value(itemId))
                .andExpect(jsonPath("$.data.nodes[0].item_id").value(itemId));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-log", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].event_type").isNotEmpty());

        MvcResult missingPage = mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-search", workspaceId)
                        .param("q", "缺失页面"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("缺失页面"))
                .andReturn();
        String missingPageId = objectMapper.readTree(missingPage.getResponse().getContentAsString()).path("data").path(0).path("item_id").asText();

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-issues", workspaceId)
                        .param("item_id", missingPageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].issue_type").value(org.hamcrest.Matchers.hasItems("MISSING_SOURCE", "PLACEHOLDER_CONTENT")));

        mockMvc.perform(patch("/api/v2/knowledge-items/{itemId}/title", missingPageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "已补齐页面"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("已补齐页面"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-graph", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.edges[0].target_title").value("已补齐页面"))
                .andExpect(jsonPath("$.data.edges[0].relation_status").value("RESOLVED"));

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}", itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outgoing_links[0].target_title").value("已补齐页面"));

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}/versions", itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version_no").value(2));

        mockMvc.perform(delete("/api/v2/knowledge-items/{itemId}", missingPageId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-search", workspaceId)
                        .param("q", "已补齐页面"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].item_id").value(itemId))
                .andExpect(jsonPath("$.data[0].unresolved_count").value(1));

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}", itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outgoing_links[0].relation_status").value("UNRESOLVED"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-log", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].event_type").value("DELETE_PAGE"))
                .andExpect(jsonPath("$.data[0].item_id").value(missingPageId));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-log", workspaceId)
                        .param("item_id", itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].item_id").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(itemId))));
    }

    @Test
    void wikiLinksShouldInferNaturalTitleMentionsWithoutExplicitBrackets() throws Exception {
        String workspaceId = createWorkspace();
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "自然目标页",
                                "content", "# 自然目标页\n\n这是一个会被自然提及命中的页面。"
                        ))))
                .andExpect(status().isOk());

        MvcResult sourcePage = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "自然提及页",
                                "content", "# 自然提及页\n\n这里会直接提到自然目标页，但不会写成显式的中括号链接。自然目标页需要被自动识别成页面关系。"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String sourceItemId = objectMapper.readTree(sourcePage.getResponse().getContentAsString()).path("data").path("item_id").asText();

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}", sourceItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("[[自然目标页]]")))
                .andExpect(jsonPath("$.data.outgoing_links[0].target_title").value("自然目标页"))
                .andExpect(jsonPath("$.data.outgoing_links[0].relation_type").value("HYBRID_LINK"))
                .andExpect(jsonPath("$.data.outgoing_links[0].relation_status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.outgoing_links[0].mention_count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void creatingMissingWikiPageShouldResolveExistingBrokenLinksImmediately() throws Exception {
        String workspaceId = createWorkspace();
        MvcResult sourcePage = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "断链来源页",
                                "content", "# 断链来源页\n\n这里先引用 [[待补齐目标页]]，用来验证手动补缺后能否直接修复断链。"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String sourceItemId = objectMapper.readTree(sourcePage.getResponse().getContentAsString()).path("data").path("item_id").asText();

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}", sourceItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outgoing_links[0].target_title").value("待补齐目标页"))
                .andExpect(jsonPath("$.data.outgoing_links[0].relation_status").value("UNRESOLVED"));

        MvcResult targetPage = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "待补齐目标页",
                                "content", "# 待补齐目标页\n\n这是手动补缺创建的页面。"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String targetItemId = objectMapper.readTree(targetPage.getResponse().getContentAsString()).path("data").path("item_id").asText();

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}", sourceItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outgoing_links[0].target_title").value("待补齐目标页"))
                .andExpect(jsonPath("$.data.outgoing_links[0].target_item_id").value(targetItemId))
                .andExpect(jsonPath("$.data.outgoing_links[0].relation_status").value("RESOLVED"));

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}", targetItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.backlinks[0].source_item_id").value(sourceItemId))
                .andExpect(jsonPath("$.data.backlinks[0].target_item_id").value(targetItemId))
                .andExpect(jsonPath("$.data.backlinks[0].relation_status").value("RESOLVED"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-issues", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].issue_type", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("BROKEN_LINK"))));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki/rebuild-advice", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommended_action").value("FOCUS_MANUAL_REPAIR"))
                .andExpect(jsonPath("$.data.recommended_issue_type").value("MISSING_SOURCE"))
                .andExpect(jsonPath("$.data.focus_item_id").value(targetItemId));
    }

    @Test
    void rebuildWikiLinksShouldRefreshAutoLinkContentForExistingPages() throws Exception {
        String workspaceId = createWorkspace();
        MvcResult sourcePage = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "延迟关联页",
                                "content", "# 延迟关联页\n\n这里先自然提到补链目标页，但创建时目标页还不存在。"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String sourceItemId = objectMapper.readTree(sourcePage.getResponse().getContentAsString()).path("data").path("item_id").asText();

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "补链目标页",
                                "content", "# 补链目标页\n\n这个页面后创建，用来验证 rebuild-links 是否会回写 Auto Link。"
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}", sourceItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("[[补链目标页]]"))));

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/wiki/rebuild-links", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolved_link_count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}", sourceItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latest_version_no").value(2))
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("[[补链目标页]]")))
                .andExpect(jsonPath("$.data.outgoing_links[0].target_title").value("补链目标页"))
                .andExpect(jsonPath("$.data.outgoing_links[0].relation_type").value("WIKI_LINK"))
                .andExpect(jsonPath("$.data.outgoing_links[0].relation_status").value("RESOLVED"));

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}/versions", sourceItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version_no").value(2))
                .andExpect(jsonPath("$.data[1].version_no").value(1));
    }

    @Test
    void renamingWikiPageShouldRefreshIncomingWikiContentAndAppendVersion() throws Exception {
        String workspaceId = createWorkspace();
        MvcResult targetPage = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "旧目标页",
                                "content", "# 旧目标页\n\n这是一张会被其他页面引用的页面。"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String targetItemId = objectMapper.readTree(targetPage.getResponse().getContentAsString()).path("data").path("item_id").asText();

        MvcResult sourcePage = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "引用页",
                                "content", "# 引用页\n\n这里使用 [[旧目标页]] 作为显式页面链接。"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latest_version_no").value(1))
                .andReturn();
        String sourceItemId = objectMapper.readTree(sourcePage.getResponse().getContentAsString()).path("data").path("item_id").asText();

        mockMvc.perform(patch("/api/v2/knowledge-items/{itemId}/title", targetItemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "新目标页"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("新目标页"));

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}", sourceItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latest_version_no").value(2))
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("[[新目标页]]")))
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("[[旧目标页]]"))))
                .andExpect(jsonPath("$.data.outgoing_links[0].target_title").value("新目标页"))
                .andExpect(jsonPath("$.data.outgoing_links[0].relation_type").value("WIKI_LINK"))
                .andExpect(jsonPath("$.data.outgoing_links[0].relation_status").value("RESOLVED"));

        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}/versions", sourceItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version_no").value(2))
                .andExpect(jsonPath("$.data[1].version_no").value(1));
    }

    @Test
    void wikiIssuesShouldMarkSourceBackedPageAsContentStaleWhenBoundSourceIsNewer() throws Exception {
        String workspaceId = createWorkspace();
        String sourceId = uploadSource(workspaceId, "stale-source.md", """
                这是第一版资料内容。
                它会先被用于生成一张带来源绑定的 Wiki 页面。
                """);
        String conversationId = createConversation(workspaceId);

        JsonNode qaMessage = sendMessage(conversationId, "QA", "请总结这份 stale-source 资料");
        String assistantMessageId = qaMessage.path("data").path("assistant_message_id").asText();
        String requestId = qaMessage.path("data").path("assistant_request_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", requestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        MvcResult page = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "资料过期页",
                                "content", "# 资料过期页\n\n这是基于第一版资料生成的 Wiki 页面。",
                                "source_message_id", assistantMessageId
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String itemId = objectMapper.readTree(page.getResponse().getContentAsString()).path("data").path("item_id").asText();

        jdbcTemplate.update("""
                update source
                set updated_at = dateadd('SECOND', 5, current_timestamp)
                where id = ?
                """, sourceId);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-issues", workspaceId)
                        .param("item_id", itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].issue_type").value(org.hamcrest.Matchers.hasItem("CONTENT_STALE")));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki/rebuild-advice", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommended_action").value("ENABLE_WIKI"))
                .andExpect(jsonPath("$.data.recommended_issue_type").value("CONTENT_STALE"))
                .andExpect(jsonPath("$.data.focus_item_id").value(itemId))
                .andExpect(jsonPath("$.data.focus_title").isNotEmpty())
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("先开启 Wiki 构建")));
    }

    @Test
    void rebuildAdviceShouldRecommendManualRepairForPlaceholderPages() throws Exception {
        String workspaceId = createWorkspace();
        MvcResult create = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "占位补缺页",
                                "content", "# 占位补缺页\n\n## 待补充\n\n该页面由 Wiki auto-fix 根据断链自动创建，需要人工补充内容和来源。\n"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String itemId = objectMapper.readTree(create.getResponse().getContentAsString()).path("data").path("item_id").asText();

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-issues", workspaceId)
                        .param("item_id", itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].issue_type").value(org.hamcrest.Matchers.hasItems("PLACEHOLDER_CONTENT", "MISSING_SOURCE")));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki/rebuild-advice", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommended_action").value("FOCUS_MANUAL_REPAIR"))
                .andExpect(jsonPath("$.data.recommended_issue_type").value("PLACEHOLDER_CONTENT"))
                .andExpect(jsonPath("$.data.focus_item_id").value(itemId))
                .andExpect(jsonPath("$.data.focus_title").isNotEmpty())
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("占位补缺页")));
    }

    @Test
    void rebuildAdviceShouldRecommendManualRepairForMissingSourcePages() throws Exception {
        String workspaceId = createWorkspace();
        MvcResult create = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "缺少来源页",
                                "content", "# 缺少来源页\n\n这里已经有正文，但还没有补来源引用。"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String itemId = objectMapper.readTree(create.getResponse().getContentAsString()).path("data").path("item_id").asText();

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-issues", workspaceId)
                        .param("item_id", itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].issue_type").value(org.hamcrest.Matchers.hasItems("MISSING_SOURCE", "ORPHAN_PAGE")));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki/rebuild-advice", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommended_action").value("FOCUS_MANUAL_REPAIR"))
                .andExpect(jsonPath("$.data.recommended_issue_type").value("MISSING_SOURCE"))
                .andExpect(jsonPath("$.data.focus_item_id").value(itemId))
                .andExpect(jsonPath("$.data.focus_title").isNotEmpty())
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("缺少来源引用")));
    }

    @Test
    void rebuildAdviceShouldRecommendManualRepairForOrphanPagesWhenNoHigherPriorityIssueExists() throws Exception {
        String workspaceId = createWorkspace();
        mockMvc.perform(put("/api/v2/workspaces/{workspaceId}/wiki-settings", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("wiki_enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wiki_enabled").value(true));
        uploadSource(workspaceId, "orphan-source.md", """
                这份资料会被用于创建一张只有来源、但没有页面关系的 Wiki 页面。
                """);
        String conversationId = createConversation(workspaceId);

        JsonNode qaMessage = sendMessage(conversationId, "QA", "请总结 orphan-source 资料");
        String assistantMessageId = qaMessage.path("data").path("assistant_message_id").asText();
        String requestId = qaMessage.path("data").path("assistant_request_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", requestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        MvcResult create = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "孤立页面",
                                "content", "# 孤立页面\n\n这张页面有来源，但当前还没有任何页面关系。",
                                "source_message_id", assistantMessageId
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String itemId = objectMapper.readTree(create.getResponse().getContentAsString()).path("data").path("item_id").asText();

        jdbcTemplate.update("delete from knowledge_item_link where workspace_id = ?", workspaceId);
        jdbcTemplate.update("""
                update knowledge_item
                set status = 'DELETED', updated_at = current_timestamp
                where workspace_id = ? and item_type = 'WIKI' and id <> ?
                """, workspaceId, itemId);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki-issues", workspaceId)
                        .param("item_id", itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].issue_type").value(org.hamcrest.Matchers.hasItem("ORPHAN_PAGE")))
                .andExpect(jsonPath("$.data[*].issue_type").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("MISSING_SOURCE"))));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki/rebuild-advice", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommended_action").value("FOCUS_MANUAL_REPAIR"))
                .andExpect(jsonPath("$.data.recommended_issue_type").value("ORPHAN_PAGE"))
                .andExpect(jsonPath("$.data.focus_item_id").value(itemId))
                .andExpect(jsonPath("$.data.focus_title").isNotEmpty())
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("孤立")));
    }

    @Test
    void rebuildAdviceShouldRecommendRebuildWhenWikiEnabledAndPagesAreStale() throws Exception {
        String workspaceId = createWorkspace();
        mockMvc.perform(put("/api/v2/workspaces/{workspaceId}/wiki-settings", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("wiki_enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wiki_enabled").value(true));

        String sourceId = uploadSource(workspaceId, "stale-enabled.md", """
                这是启用 Wiki 后的第一版资料。
                后续会通过更新时间制造 CONTENT_STALE。
                """);
        String conversationId = createConversation(workspaceId);

        JsonNode qaMessage = sendMessage(conversationId, "QA", "请总结这份 stale-enabled 资料");
        String assistantMessageId = qaMessage.path("data").path("assistant_message_id").asText();
        String requestId = qaMessage.path("data").path("assistant_request_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", requestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "已启用的过期页",
                                "content", "# 已启用的过期页\n\n这是基于旧资料版本生成的页面。",
                                "source_message_id", assistantMessageId
                        ))))
                .andExpect(status().isOk());

        jdbcTemplate.update("""
                update source
                set updated_at = dateadd('SECOND', 5, current_timestamp)
                where id = ?
                """, sourceId);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/wiki/rebuild-advice", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommended_action").value("REBUILD_WIKI"))
                .andExpect(jsonPath("$.data.recommended_issue_type").value("CONTENT_STALE"))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("重建 Wiki")));
    }

    @Test
    void noteModeShouldCarryRollingConversationContextForFollowUpQuestions() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId, "alpha-note.md", """
                AnchorAlpha 的资料定位强调先锁定候选资料，再打开原文窗口提取可验证片段。
                AnchorAlpha 的关键要求包括按主题连续深读，而不是把每一轮追问都当作孤立检索。
                AnchorAlpha 的第二点关键要求是证据窗口要持续围绕同一主题，避免检索漂移。
                """);
        uploadSource(workspaceId, "beta-note.md", """
                BetaNotebook 主要讨论批量导入和离线解析。
                它不是 AnchorAlpha 的同主题资料。
                """);
        String conversationId = createConversation(workspaceId);

        sendMessage(conversationId, "NOTE", "请整理 AnchorAlpha 的资料定位");
        sendMessage(conversationId, "NOTE", "把关键要求分成两点整理");
        JsonNode followUp = sendMessage(conversationId, "NOTE", "第二点为什么重要");
        String assistantRequestId = followUp.path("data").path("assistant_request_id").asText();
        String assistantMessageId = followUp.path("data").path("assistant_message_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", assistantRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("会话上下文：已纳入最近连续对话窗口")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("连续对话窗口：最近 2 轮相关对话")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("主题锚点：")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AnchorAlpha")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        Integer alphaCitationCount = jdbcTemplate.queryForObject("""
                select count(*)
                from message_citation mc
                join citation c on c.id = mc.citation_id
                where mc.message_id = ? and c.title = 'alpha-note.md'
                """, Integer.class, assistantMessageId);
        assertThat(alphaCitationCount).isNotNull().isGreaterThan(0);
    }

    @Test
    void wikiModeShouldCarryRollingConversationContextForFollowUpQuestions() throws Exception {
        String workspaceId = createWorkspace();
        createManualWikiPage(workspaceId, "Alpha执行", """
                # Alpha执行

                Alpha执行页描述 Alpha策略 的具体落地步骤和验证动作。
                """);
        createManualWikiPage(workspaceId, "Alpha策略", """
                # Alpha策略

                Alpha策略页面说明研究工作台如何围绕单一主题组织资料与回答。

                ## 关联页面

                - 具体执行见 [[Alpha执行]]
                """);
        createManualWikiPage(workspaceId, "Beta策略", """
                # Beta策略

                Beta策略页面讨论另一套完全不同的工作流。
                """);
        String conversationId = createConversation(workspaceId);

        sendMessage(conversationId, "WIKI", "请介绍 Alpha策略");
        sendMessage(conversationId, "WIKI", "把它分成页面定位和关联页面两点讲");
        JsonNode followUp = sendMessage(conversationId, "WIKI", "第二点为什么成立");
        String assistantRequestId = followUp.path("data").path("assistant_request_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", assistantRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("本轮已结合最近连续对话窗口理解这次追问。")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("连续对话窗口：最近 2 轮相关对话")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("主题锚点：")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Alpha策略")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Alpha执行")));
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

    private String createManualWikiPage(String workspaceId, String title, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", title,
                                "content", content
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_type").value("WIKI"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("item_id").asText();
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
