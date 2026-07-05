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
import java.util.List;
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
class Phase5MemoryContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void memorySignalPromotionShouldProduceChatArtifactAndResearchControlPacks() throws Exception {
        String workspaceId = createWorkspace();

        String chatSignalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "PREFERENCE",
                "source_type", "USER_FEEDBACK",
                "signal_text", "聊天回答保持结论先行，统一使用研究工作台这个术语",
                "task_neighborhood", "CHAT_QA",
                "style_constraints", List.of("结论先行"),
                "terminology_policy", List.of("统一使用研究工作台")
        ));
        String artifactSignalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "FORMAT",
                "source_type", "PROJECT_DECISION",
                "signal_text", "报告输出统一使用问题-方法-效果结构",
                "task_neighborhood", "ARTIFACT_REPORT",
                "structure_constraints", List.of("问题-方法-效果"),
                "forbidden_patterns", List.of("废弃说明")
        ));
        String researchSignalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "DECISION",
                "source_type", "PROJECT_DECISION",
                "signal_text", "研究报告先给关键结论再给证据摘要",
                "task_neighborhood", "RESEARCH_DEFAULT",
                "structure_constraints", List.of("先关键结论后证据摘要")
        ));

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("signal_ids", List.of(chatSignalId, artifactSignalId, researchSignalId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memory_objects.length()").value(3));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/control-pack/chat", workspaceId)
                        .param("answer_mode", "QA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pack_type").value("chat"))
                .andExpect(jsonPath("$.data.style_constraints[0]").value("结论先行"))
                .andExpect(jsonPath("$.data.terminology_policy[0]").value("统一使用研究工作台"))
                .andExpect(jsonPath("$.data.evidence_policy[0]").value(org.hamcrest.Matchers.containsString("Memory 不作为事实来源")));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/control-pack/artifact", workspaceId)
                        .param("action_key", "report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pack_type").value("artifact"))
                .andExpect(jsonPath("$.data.structure_constraints[0]").value("问题-方法-效果"))
                .andExpect(jsonPath("$.data.forbidden_patterns[0]").value("废弃说明"))
                .andExpect(jsonPath("$.data.evidence_policy[1]").value(org.hamcrest.Matchers.containsString("Memory 不作为产物生成原材料")));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/control-pack/research", workspaceId)
                        .param("profile_key", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pack_type").value("research"))
                .andExpect(jsonPath("$.data.structure_constraints[0]").value("先关键结论后证据摘要"))
                .andExpect(jsonPath("$.data.evidence_policy[0]").value(org.hamcrest.Matchers.containsString("Memory 不作为研究证据")));
    }

    @Test
    void qaNoteAndWikiChainsShouldApplyChatControlPackWithoutBreakingCoreFlows() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId, "memory-alpha.md", """
                AlphaMemory 说明研究工作台里的回答应该先给结论，再给依据和引用。
                AlphaMemory 还说明 Note 链路和 Wiki 链路都不能把 Memory 当作事实来源。
                """);
        String conversationId = createConversation(workspaceId);

        String signalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "PREFERENCE",
                "source_type", "USER_FEEDBACK",
                "signal_text", "回答保持结论先行，统一使用研究工作台",
                "task_neighborhood", "CHAT",
                "style_constraints", List.of("结论先行"),
                "terminology_policy", List.of("统一使用研究工作台"),
                "interaction_policy", List.of("优先直接给回答，再补说明")
        ));
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("signal_ids", List.of(signalId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memory_objects.length()").value(1));

        JsonNode qaMessage = sendMessage(conversationId, "QA", "请总结 AlphaMemory 的关键要求");
        String qaRequestId = qaMessage.path("data").path("assistant_request_id").asText();
        String qaAssistantMessageId = qaMessage.path("data").path("assistant_message_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", qaRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("结论先行")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("统一使用研究工作台")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        mockMvc.perform(post("/api/v2/messages/{messageId}/save-as-note", qaAssistantMessageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Memory Note"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_type").value("NOTE"));

        JsonNode noteMessage = sendMessage(conversationId, "NOTE", "继续整理 AlphaMemory");
        String noteRequestId = noteMessage.path("data").path("assistant_request_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", noteRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("结论先行")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        createManualWikiPage(workspaceId, "AlphaMemory 页面", """
                # AlphaMemory 页面

                AlphaMemory 页面强调研究工作台里的知识回答要保留引用和页面关系。
                """);
        JsonNode wikiMessage = sendMessage(conversationId, "WIKI", "请介绍 AlphaMemory 页面");
        String wikiRequestId = wikiMessage.path("data").path("assistant_request_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", wikiRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("统一使用研究工作台")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AlphaMemory 页面")));

        Integer usageCount = jdbcTemplate.queryForObject(
                "select count(*) from memory_usage_log where workspace_id = ? and compiled_as = 'chat'",
                Integer.class,
                workspaceId
        );
        assertThat(usageCount).isNotNull().isGreaterThanOrEqualTo(3);
    }

    @Test
    void rollingContextWindowAndMemoryShouldWorkTogetherAcrossQaNoteAndWiki() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId, "alpha-workbench.md", """
                AlphaWorkbench 是一个研究工作台规范，强调四个核心原则：
                第一，资料要围绕统一工作台组织，回答默认基于当前工作台全部资料。
                第二，证据要可回跳，回答必须保留引用与来源依据。
                第三，连续对话要围绕同一主题推进，避免每轮都把问题当成孤立查询。
                第四，三条聊天链路都在同一聊天页回答，但背后的检索方式不同。
                """);
        uploadSource(workspaceId, "alpha-workbench-note.md", """
                AlphaWorkbench 补充说明：
                Note 链路先定位资料，再打开原文窗口，最后给出带引用的整理结果。
                Wiki 链路读取工作台内的 Wiki 页面网络，但不拿 Memory 代替页面事实。
                """);
        createManualWikiPage(workspaceId, "AlphaWorkbench 页面", """
                # AlphaWorkbench 页面

                AlphaWorkbench 页面强调同一聊天页支持 QA、Note、Wiki 三种回答链路，
                并要求连续追问时保留主题锚点、前序主题摘要和来源回链能力。
                """);
        String conversationId = createConversation(workspaceId);

        String signalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "PREFERENCE",
                "source_type", "USER_FEEDBACK",
                "signal_text", "回答保持先结论后结构，统一使用研究工作台这个术语",
                "task_neighborhood", "CHAT",
                "style_constraints", List.of("先结论后结构"),
                "terminology_policy", List.of("统一使用研究工作台"),
                "interaction_policy", List.of("先直接回答，再补证据说明")
        ));
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("signal_ids", List.of(signalId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memory_objects.length()").value(1));

        sendMessage(conversationId, "QA", "先介绍 AlphaWorkbench 的核心原则");
        sendMessage(conversationId, "QA", "AlphaWorkbench 的四个原则分别是什么");
        sendMessage(conversationId, "QA", "AlphaWorkbench 的第二个原则为什么重要");
        sendMessage(conversationId, "QA", "AlphaWorkbench 的第三个原则解决什么问题");

        JsonNode qaFollowUp = sendMessage(conversationId, "QA", "继续总结 AlphaWorkbench 这些原则的共同目标");
        String qaRequestId = qaFollowUp.path("data").path("assistant_request_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", qaRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("前序主题摘要")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("先结论后结构")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("统一使用研究工作台")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        JsonNode noteFollowUp = sendMessage(conversationId, "NOTE", "继续整理 AlphaWorkbench 的资料要点");
        String noteRequestId = noteFollowUp.path("data").path("assistant_request_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", noteRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("前序主题摘要")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("【原文窗口】")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("先结论后结构")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        JsonNode wikiFollowUp = sendMessage(conversationId, "WIKI", "继续用 Wiki 说明 AlphaWorkbench 页面");
        String wikiRequestId = wikiFollowUp.path("data").path("assistant_request_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", wikiRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("前序主题摘要")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AlphaWorkbench 页面")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("统一使用研究工作台")));

        Integer usageCount = jdbcTemplate.queryForObject(
                "select count(*) from memory_usage_log where workspace_id = ? and compiled_as = 'chat'",
                Integer.class,
                workspaceId
        );
        assertThat(usageCount).isNotNull().isGreaterThanOrEqualTo(3);
    }

    @Test
    void memoryEnabledChatChainsShouldCutOffOldTopicAcrossQaNoteAndWiki() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId, "alpha-drift.md", """
                AlphaDesk 关注旧主题下的阶段拆分、证据回链和连续对话追问。
                这份资料只用于制造旧主题上下文，后续切到新主题时不应该继续污染检索窗口。
                """);
        uploadSource(workspaceId, "beta-drift.md", """
                BetaDesk 关注新的工作台资料定位方式、原文窗口深读和页面化知识沉淀。
                当用户显式切到 BetaDesk 时，系统应该直接围绕这个新主题回答，而不是继续沿用 AlphaDesk 的连续对话窗口。
                """);
        createManualWikiPage(workspaceId, "AlphaDesk 页面", """
                # AlphaDesk 页面

                AlphaDesk 页面只描述旧主题的阶段拆分与证据回链。
                """);
        createManualWikiPage(workspaceId, "BetaDesk 页面", """
                # BetaDesk 页面

                BetaDesk 页面强调新的资料定位、原文窗口深读和页面化知识沉淀。
                """);

        String signalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "PREFERENCE",
                "source_type", "USER_FEEDBACK",
                "signal_text", "回答保持先结论后结构，统一使用研究工作台这个术语",
                "task_neighborhood", "CHAT",
                "style_constraints", List.of("先结论后结构"),
                "terminology_policy", List.of("统一使用研究工作台"),
                "interaction_policy", List.of("优先直接回答，再补证据说明")
        ));
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("signal_ids", List.of(signalId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memory_objects.length()").value(1));

        String qaConversationId = createConversation(workspaceId);
        sendMessage(qaConversationId, "QA", "先介绍 AlphaDesk 的旧主题原则");
        JsonNode qaShift = sendMessage(qaConversationId, "QA", "请介绍 BetaDesk 的资料定位要求");
        String qaRequestId = qaShift.path("data").path("assistant_request_id").asText();
        String qaAssistantMessageId = qaShift.path("data").path("assistant_message_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", qaRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("先结论后结构")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("统一使用研究工作台")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BetaDesk")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("会话上下文：已纳入最近连续对话窗口"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("前序主题摘要："))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));
        Integer qaBetaCitationCount = jdbcTemplate.queryForObject("""
                select count(*)
                from message_citation mc
                join citation c on c.id = mc.citation_id
                where mc.message_id = ? and c.title = 'beta-drift.md'
                """, Integer.class, qaAssistantMessageId);
        assertThat(qaBetaCitationCount).isNotNull().isGreaterThan(0);

        String noteConversationId = createConversation(workspaceId);
        sendMessage(noteConversationId, "NOTE", "先整理 AlphaDesk 的旧主题资料");
        JsonNode noteShift = sendMessage(noteConversationId, "NOTE", "请整理 BetaDesk 的资料定位");
        String noteRequestId = noteShift.path("data").path("assistant_request_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", noteRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("统一使用研究工作台")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BetaDesk")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("【原文窗口】")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("会话上下文：已纳入最近连续对话窗口"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("前序主题摘要："))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        String wikiConversationId = createConversation(workspaceId);
        sendMessage(wikiConversationId, "WIKI", "先介绍 AlphaDesk 页面");
        JsonNode wikiShift = sendMessage(wikiConversationId, "WIKI", "请介绍 BetaDesk 页面");
        String wikiRequestId = wikiShift.path("data").path("assistant_request_id").asText();
        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", wikiRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("统一使用研究工作台")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BetaDesk 页面")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("本轮已结合最近连续对话窗口理解这次追问。"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("## 会话上下文"))));

        Integer usageCount = jdbcTemplate.queryForObject(
                "select count(*) from memory_usage_log where workspace_id = ? and compiled_as = 'chat'",
                Integer.class,
                workspaceId
        );
        assertThat(usageCount).isNotNull().isGreaterThanOrEqualTo(3);
    }

    @Test
    void graduatedMemoryGatesShouldRejectWeakSignalsMergeDuplicatesAndCompileNegativeMemory() throws Exception {
        String workspaceId = createWorkspace();

        String weakSignalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "PREFERENCE",
                "source_type", "MODEL_INFERENCE",
                "signal_text", "系统推测用户可能喜欢更长的解释",
                "task_neighborhood", "CHAT",
                "style_constraints", List.of("输出更长解释")
        ));
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("signal_ids", List.of(weakSignalId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].evidence_gate_status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.data.candidates[0].review_status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.data.memory_objects.length()").value(0));

        String negativeSignalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "NEGATIVE",
                "source_type", "USER_FEEDBACK",
                "signal_text", "正式文档不要写废弃说明或历史演进",
                "task_neighborhood", "CHAT_QA",
                "forbidden_patterns", List.of("废弃说明", "历史演进")
        ));
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("signal_ids", List.of(negativeSignalId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].negative_memory").value(true))
                .andExpect(jsonPath("$.data.candidates[0].review_status").value("READY"))
                .andExpect(jsonPath("$.data.memory_objects.length()").value(1))
                .andExpect(jsonPath("$.data.memory_objects[0].memory_type").value("NEGATIVE"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/control-pack/chat", workspaceId)
                        .param("answer_mode", "QA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.forbidden_patterns").value(org.hamcrest.Matchers.hasItems("废弃说明", "历史演进")))
                .andExpect(jsonPath("$.data.memory_object_ids.length()").value(1));

        String duplicateNegativeSignalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "NEGATIVE",
                "source_type", "USER_FEEDBACK",
                "signal_text", "正式文档不要写废弃说明或历史演进",
                "task_neighborhood", "CHAT_QA",
                "forbidden_patterns", List.of("废弃说明", "历史演进")
        ));
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("signal_ids", List.of(duplicateNegativeSignalId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].novelty_score").value(org.hamcrest.Matchers.lessThan(0.5)))
                .andExpect(jsonPath("$.data.candidates[0].conflict_status").value("EXISTING_EQUIVALENT"))
                .andExpect(jsonPath("$.data.memory_objects.length()").value(1));

        Integer activeMemoryCount = jdbcTemplate.queryForObject(
                "select count(*) from memory_object where workspace_id = ? and status = 'ACTIVE'",
                Integer.class,
                workspaceId
        );
        assertThat(activeMemoryCount).isEqualTo(1);

        Map<String, Object> ledgerRow = jdbcTemplate.queryForMap(
                "select ledger_json from memory_object where workspace_id = ? and status = 'ACTIVE'",
                workspaceId
        );
        String ledgerJson = (String) ledgerRow.get("ledger_json");
        assertThat(ledgerJson)
                .contains("source_signal_ids")
                .contains("evidence_gate_status")
                .contains("novelty_score")
                .contains("marginal_utility_score")
                .contains("promoted_from_candidate_id");
    }

    @Test
    void graduatedMemoryGatesShouldHoldConflictingSignalForReview() throws Exception {
        String workspaceId = createWorkspace();

        String positiveSignalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "PREFERENCE",
                "source_type", "USER_FEEDBACK",
                "signal_text", "正式文档统一使用先结论后结构",
                "task_neighborhood", "CHAT_QA",
                "style_constraints", List.of("先结论后结构")
        ));
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("signal_ids", List.of(positiveSignalId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memory_objects.length()").value(1));

        String conflictingNegativeSignalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "NEGATIVE",
                "source_type", "USER_FEEDBACK",
                "signal_text", "正式文档统一使用先结论后结构",
                "task_neighborhood", "CHAT_QA",
                "forbidden_patterns", List.of("先结论后结构")
        ));
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("signal_ids", List.of(conflictingNegativeSignalId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].conflict_status").value("CONFLICTING_ACTIVE_MEMORY"))
                .andExpect(jsonPath("$.data.candidates[0].review_status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.data.memory_objects.length()").value(0));

        Integer activeMemoryCount = jdbcTemplate.queryForObject(
                "select count(*) from memory_object where workspace_id = ? and status = 'ACTIVE'",
                Integer.class,
                workspaceId
        );
        assertThat(activeMemoryCount).isEqualTo(1);
    }

    private String createWorkspace() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Memory 工作台",
                                "description", "用于 Memory 契约测试"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("workspace_id").asText();
    }

    private String createConversation(String workspaceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Memory 聊天",
                                "conversation_type", "WORKSPACE_CHAT"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("conversation_id").asText();
    }

    private void uploadSource(String workspaceId, String fileName, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        MvcResult init = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/uploads", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "file_name", fileName,
                                "file_size", bytes.length,
                                "mime_type", "text/markdown",
                                "chunk_size", bytes.length,
                                "total_chunks", 1
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String uploadId = objectMapper.readTree(init.getResponse().getContentAsString()).path("data").path("upload_id").asText();
        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, 0)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(bytes))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v2/uploads/{uploadId}/complete", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parse_status").value("PARSED"))
                .andExpect(jsonPath("$.data.index_status").value("INDEXED"));
    }

    private String createMemorySignal(String workspaceId, Map<String, Object> payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/signals", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("signal_id").asText();
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
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void createManualWikiPage(String workspaceId, String title, String content) throws Exception {
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", title,
                                "content", content
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_type").value("WIKI"));
    }
}
