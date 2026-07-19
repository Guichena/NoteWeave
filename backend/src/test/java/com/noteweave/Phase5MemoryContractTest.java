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
        String researchSourceId = uploadSource(
                workspaceId,
                "memory-outcome-provenance.md",
                "# Memory outcome provenance\n\nA verified source fixture for the research control-pack contract.");

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
                        .param("skill_key", "report_draft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pack_type").value("artifact"))
                .andExpect(jsonPath("$.data.target_key").value("report_draft"))
                .andExpect(jsonPath("$.data.task_neighborhood").value("ARTIFACT_SKILL_REPORT_DRAFT"))
                .andExpect(jsonPath("$.data.structure_constraints[0]").value("问题-方法-效果"))
                .andExpect(jsonPath("$.data.forbidden_patterns[0]").value("废弃说明"))
                .andExpect(jsonPath("$.data.evidence_policy[1]").value(org.hamcrest.Matchers.containsString("Memory 不作为产物生成原材料")));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/control-pack/research", workspaceId)
                        .param("profile_key", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pack_type").value("research"))
                .andExpect(jsonPath("$.data.structure_constraints[0]").value("先关键结论后证据摘要"))
                .andExpect(jsonPath("$.data.evidence_policy[0]").value(org.hamcrest.Matchers.containsString("Memory 不作为研究证据")));

        MvcResult artifactResult = mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "report_draft",
                                "user_requirement", "生成 outcome provenance 测试报告",
                                "inputs", Map.of("language", "zh-CN")
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String artifactTaskId = objectMapper.readTree(
                        artifactResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        MvcResult researchResult = mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/research-runs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "如何验证 Memory outcome provenance？",
                                "profile", "default",
                                "source_scope_source_ids", List.of(researchSourceId)
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String researchRunId = objectMapper.readTree(
                        researchResult.getResponse().getContentAsString())
                .path("data").path("research_run_id").asText();

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from memory_usage_log
                where workspace_id = ? and target_type = 'ARTIFACT_JOB_RUN'
                  and target_id = ? and memory_version_id is not null
                """, Integer.class, workspaceId, artifactTaskId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from memory_usage_log
                where workspace_id = ? and target_type = 'RESEARCH_RUN'
                  and target_id = ? and memory_version_id is not null
                """, Integer.class, workspaceId, researchRunId)).isEqualTo(1);
    }

    @Test
    void promotedMemoryShouldCreateImmutableInitialVersionAndCompilerShouldReadLatestVersion() throws Exception {
        String workspaceId = createWorkspace();
        String signalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "PREFERENCE",
                "source_type", "USER_FEEDBACK",
                "signal_text", "版本化 Memory 回答必须先给稳定结论",
                "task_neighborhood", "CHAT_QA",
                "style_constraints", List.of("先给稳定结论")
        ));

        MvcResult promotionResult = mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "signal_ids", List.of(signalId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memory_objects.length()").value(1))
                .andReturn();
        JsonNode promotion = objectMapper.readTree(
                promotionResult.getResponse().getContentAsString());
        String memoryObjectId = promotion.path("data").path("memory_objects")
                .get(0).path("memory_object_id").asText();
        String candidateId = promotion.path("data").path("candidates")
                .get(0).path("candidate_id").asText();

        Map<String, Object> pointer = jdbcTemplate.queryForMap("""
                select latest_version_id, current_version_no
                from memory_object
                where workspace_id = ? and id = ?
                """, workspaceId, memoryObjectId);
        String memoryVersionId = (String) pointer.get("latest_version_id");
        assertThat(memoryVersionId).isNotBlank();
        assertThat(((Number) pointer.get("current_version_no")).intValue()).isEqualTo(1);

        mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/memory/objects/{memoryObjectId}/versions",
                        workspaceId, memoryObjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].memory_version_id").value(memoryVersionId))
                .andExpect(jsonPath("$.data[0].version_no").value(1))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].created_from_candidate_id").value(candidateId))
                .andExpect(jsonPath("$.data[0].policy_version")
                        .value("memory-candidate-policy-v1"))
                .andExpect(jsonPath("$.data[0].scope_status").value("VALID"))
                .andExpect(jsonPath("$.data[0].compile_hints.style_constraints[0]")
                        .value("先给稳定结论"));

        mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/memory/objects/{memoryObjectId}/versions/{memoryVersionId}",
                        workspaceId, memoryObjectId, memoryVersionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canonical_statement")
                        .value("版本化 Memory 回答必须先给稳定结论"));

        jdbcTemplate.update("""
                update memory_object
                set compile_policy_json = '{}'
                where workspace_id = ? and id = ?
                """, workspaceId, memoryObjectId);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/control-pack/chat", workspaceId)
                        .param("answer_mode", "QA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.style_constraints[0]")
                        .value("先给稳定结论"))
                .andExpect(jsonPath("$.data.memory_object_ids[0]")
                        .value(memoryObjectId));

        Integer versionCount = jdbcTemplate.queryForObject("""
                select count(*) from memory_version
                where workspace_id = ? and memory_object_id = ?
                """, Integer.class, workspaceId, memoryObjectId);
        assertThat(versionCount).isEqualTo(1);

        MvcResult appendResult = mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/memory/objects/{memoryObjectId}/versions",
                        workspaceId, memoryObjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "canonical_statement", "版本化 Memory 回答必须先给更新后的结论",
                                "task_neighborhoods", List.of("CHAT_QA"),
                                "style_constraints", List.of("先给更新后的结论")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version_no").value(2))
                .andExpect(jsonPath("$.data.supersedes_version_id").value(memoryVersionId))
                .andExpect(jsonPath("$.data.policy_version")
                        .value("memory-lifecycle-policy-v1"))
                .andReturn();
        String secondVersionId = objectMapper.readTree(
                        appendResult.getResponse().getContentAsString())
                .path("data").path("memory_version_id").asText();

        mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/memory/objects/{memoryObjectId}/versions",
                        workspaceId, memoryObjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].memory_version_id").value(secondVersionId))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[1].memory_version_id").value(memoryVersionId))
                .andExpect(jsonPath("$.data[1].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.data[1].canonical_statement")
                        .value("版本化 Memory 回答必须先给稳定结论"))
                .andExpect(jsonPath("$.data[1].valid_to").isNotEmpty());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/control-pack/chat", workspaceId)
                        .param("answer_mode", "QA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.style_constraints[0]")
                        .value("先给更新后的结论"));

        mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/memory/objects/{memoryObjectId}/revoke",
                        workspaceId, memoryObjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memory_version_id").value(secondVersionId))
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andExpect(jsonPath("$.data.valid_to").isNotEmpty());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/control-pack/chat", workspaceId)
                        .param("answer_mode", "QA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.style_constraints.length()").value(0))
                .andExpect(jsonPath("$.data.memory_object_ids.length()").value(0));

        Map<String, Object> revokedObject = jdbcTemplate.queryForMap("""
                select latest_version_id, current_version_no, status
                from memory_object
                where workspace_id = ? and id = ?
                """, workspaceId, memoryObjectId);
        assertThat(revokedObject.get("latest_version_id")).isEqualTo(secondVersionId);
        assertThat(((Number) revokedObject.get("current_version_no")).intValue()).isEqualTo(2);
        assertThat(revokedObject.get("status")).isEqualTo("REVOKED");
    }

    @Test
    void memoryOutcomeShouldAttributeVersionLowerUtilityAndMoveRepeatedFailuresToStale() throws Exception {
        String workspaceId = createWorkspace();
        String signalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "PREFERENCE",
                "source_type", "USER_FEEDBACK",
                "signal_text", "OutcomeMemory 回答必须保持简洁",
                "task_neighborhood", "CHAT_QA",
                "style_constraints", List.of("保持简洁")
        ));
        MvcResult promotionResult = mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "signal_ids", List.of(signalId)))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode promotion = objectMapper.readTree(
                promotionResult.getResponse().getContentAsString());
        String memoryObjectId = promotion.path("data").path("memory_objects")
                .get(0).path("memory_object_id").asText();
        String memoryVersionId = jdbcTemplate.queryForObject("""
                select latest_version_id from memory_object
                where workspace_id = ? and id = ?
                """, String.class, workspaceId, memoryObjectId);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/control-pack/chat", workspaceId)
                        .param("answer_mode", "QA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memory_references[0].memory_object_id")
                        .value(memoryObjectId))
                .andExpect(jsonPath("$.data.memory_references[0].memory_version_id")
                        .value(memoryVersionId))
                .andExpect(jsonPath("$.data.memory_references[0].selection_reason")
                        .value(org.hamcrest.Matchers.containsString("neighborhood_priority=0")))
                .andExpect(jsonPath("$.data.compilation_trace.policy_version")
                        .value("memory-compiler-policy-v1"))
                .andExpect(jsonPath("$.data.compilation_trace.maximum_tokens").value(320))
                .andExpect(jsonPath("$.data.compilation_trace.candidate_count").value(1))
                .andExpect(jsonPath("$.data.compilation_trace.selected_count").value(1))
                .andExpect(jsonPath("$.data.compilation_trace.degraded").value(false));

        String conversationId = createConversation(workspaceId);
        String firstMessageId = sendMessage(
                conversationId, "QA", "第一次应用 OutcomeMemory")
                .path("data").path("assistant_message_id").asText();
        String secondMessageId = sendMessage(
                conversationId, "QA", "第二次应用 OutcomeMemory")
                .path("data").path("assistant_message_id").asText();
        String thirdMessageId = sendMessage(
                conversationId, "QA", "第三次应用 OutcomeMemory")
                .path("data").path("assistant_message_id").asText();

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from memory_usage_log
                where workspace_id = ? and memory_object_id = ?
                  and memory_version_id = ? and target_type = 'CONVERSATION_MESSAGE'
                """, Integer.class, workspaceId, memoryObjectId, memoryVersionId))
                .isEqualTo(3);

        MvcResult firstOutcomeResult = recordMemoryOutcome(
                workspaceId, firstMessageId, "NEGATIVE");
        JsonNode firstOutcome = objectMapper.readTree(
                firstOutcomeResult.getResponse().getContentAsString())
                .path("data").path("outcomes").get(0);
        assertThat(firstOutcome.path("memory_version_id").asText())
                .isEqualTo(memoryVersionId);
        assertThat(firstOutcome.path("utility_after").asDouble())
                .isLessThan(firstOutcome.path("utility_before").asDouble());
        assertThat(firstOutcome.path("object_status").asText()).isEqualTo("ACTIVE");
        assertThat(firstOutcome.path("review_status").asText())
                .isEqualTo("REVIEW_REQUIRED");

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/control-pack/chat", workspaceId)
                        .param("answer_mode", "QA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memory_object_ids.length()").value(0));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/reviews", workspaceId)
                        .param("kind", "OBJECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].review_id").value(memoryObjectId))
                .andExpect(jsonPath("$.data[0].review_status")
                        .value("REVIEW_REQUIRED"));
        decideMemoryReview(workspaceId, "OBJECT", memoryObjectId, "APPROVE");
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/control-pack/chat", workspaceId)
                        .param("answer_mode", "QA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memory_object_ids[0]")
                        .value(memoryObjectId));

        recordMemoryOutcome(workspaceId, secondMessageId, "NEGATIVE");
        MvcResult thirdOutcomeResult = recordMemoryOutcome(
                workspaceId, thirdMessageId, "NEGATIVE");
        JsonNode thirdOutcome = objectMapper.readTree(
                thirdOutcomeResult.getResponse().getContentAsString())
                .path("data").path("outcomes").get(0);
        assertThat(thirdOutcome.path("application_count").asInt()).isEqualTo(3);
        assertThat(thirdOutcome.path("negative_outcome_count").asInt()).isEqualTo(3);
        assertThat(thirdOutcome.path("object_status").asText()).isEqualTo("STALE");

        Map<String, Object> lifecycle = jdbcTemplate.queryForMap("""
                select utility_score, application_count, negative_outcome_count,
                       status, review_status, outcome_policy_version
                from memory_object
                where workspace_id = ? and id = ?
                """, workspaceId, memoryObjectId);
        assertThat(((Number) lifecycle.get("utility_score")).doubleValue()).isLessThan(0.40);
        assertThat(((Number) lifecycle.get("application_count")).intValue()).isEqualTo(3);
        assertThat(((Number) lifecycle.get("negative_outcome_count")).intValue()).isEqualTo(3);
        assertThat(lifecycle.get("status")).isEqualTo("STALE");
        assertThat(lifecycle.get("review_status")).isEqualTo("REVIEW_REQUIRED");
        assertThat(lifecycle.get("outcome_policy_version"))
                .isEqualTo("memory-outcome-policy-v1");

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/outcomes", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "target_type", "CONVERSATION_MESSAGE",
                                "target_id", firstMessageId,
                                "outcome_type", "NEGATIVE"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMORY_APPLICATION_NOT_FOUND"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/reviews", workspaceId)
                        .param("kind", "OBJECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].review_id").value(memoryObjectId))
                .andExpect(jsonPath("$.data[0].lifecycle_status").value("STALE"))
                .andExpect(jsonPath("$.data[0].priority").value(90));
        decideMemoryReview(workspaceId, "OBJECT", memoryObjectId, "REVOKE");
        assertThat(jdbcTemplate.queryForObject("""
                select status from memory_object where workspace_id = ? and id = ?
                """, String.class, workspaceId, memoryObjectId)).isEqualTo("REVOKED");
        assertThat(jdbcTemplate.queryForObject("""
                select review_status from memory_object where workspace_id = ? and id = ?
                """, String.class, workspaceId, memoryObjectId)).isEqualTo("REJECTED");
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/reviews", workspaceId)
                        .param("kind", "OBJECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
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

        ChatStreamTestSupport.perform(mockMvc, qaRequestId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("结论先行")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("统一使用研究工作台")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:chat.citation")));

        JsonNode noteMessage = sendMessage(conversationId, "NOTE", "继续整理 AlphaMemory");
        String noteRequestId = noteMessage.path("data").path("assistant_request_id").asText();
        ChatStreamTestSupport.perform(mockMvc, noteRequestId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("结论先行")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:chat.citation")));

        createManualWikiPage(workspaceId, "AlphaMemory 页面", """
                # AlphaMemory 页面

                AlphaMemory 页面强调研究工作台里的知识回答要保留引用和页面关系。
                """);
        JsonNode wikiMessage = sendMessage(conversationId, "WIKI", "请介绍 AlphaMemory 页面");
        String wikiRequestId = wikiMessage.path("data").path("assistant_request_id").asText();
        ChatStreamTestSupport.perform(mockMvc, wikiRequestId)
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

        sendCompletedMessage(conversationId, "QA", "先介绍 AlphaWorkbench 的核心原则");
        sendCompletedMessage(conversationId, "QA", "AlphaWorkbench 的四个原则分别是什么");
        sendCompletedMessage(conversationId, "QA", "AlphaWorkbench 的第二个原则为什么重要");
        sendCompletedMessage(conversationId, "QA", "AlphaWorkbench 的第三个原则解决什么问题");

        JsonNode qaFollowUp = sendMessage(conversationId, "QA", "继续总结 AlphaWorkbench 这些原则的共同目标");
        String qaRequestId = qaFollowUp.path("data").path("assistant_request_id").asText();
        ChatStreamTestSupport.perform(mockMvc, qaRequestId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("连续对话窗口")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("先结论后结构")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("统一使用研究工作台")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:chat.citation")));

        JsonNode noteFollowUp = sendMessage(conversationId, "NOTE", "继续整理 AlphaWorkbench 的资料要点");
        String noteRequestId = noteFollowUp.path("data").path("assistant_request_id").asText();
        ChatStreamTestSupport.perform(mockMvc, noteRequestId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("连续对话窗口")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("【原文窗口】")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("先结论后结构")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:chat.citation")));

        JsonNode wikiFollowUp = sendMessage(conversationId, "WIKI", "继续用 Wiki 说明 AlphaWorkbench 页面");
        String wikiRequestId = wikiFollowUp.path("data").path("assistant_request_id").asText();
        ChatStreamTestSupport.perform(mockMvc, wikiRequestId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("连续对话窗口")))
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
        ChatStreamTestSupport.perform(mockMvc, qaRequestId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("先结论后结构")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("统一使用研究工作台")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BetaDesk")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("会话上下文：已纳入最近连续对话窗口"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("前序主题摘要："))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:chat.citation")));
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
        ChatStreamTestSupport.perform(mockMvc, noteRequestId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 表达控制")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("统一使用研究工作台")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BetaDesk")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("【原文窗口】")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("会话上下文：已纳入最近连续对话窗口"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("前序主题摘要："))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:chat.citation")));

        String wikiConversationId = createConversation(workspaceId);
        sendMessage(wikiConversationId, "WIKI", "先介绍 AlphaDesk 页面");
        JsonNode wikiShift = sendMessage(wikiConversationId, "WIKI", "请介绍 BetaDesk 页面");
        String wikiRequestId = wikiShift.path("data").path("assistant_request_id").asText();
        ChatStreamTestSupport.perform(mockMvc, wikiRequestId)
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
                .andExpect(jsonPath("$.data.candidates[0].policy_version")
                        .value("memory-candidate-policy-v1"))
                .andExpect(jsonPath("$.data.candidates[0].scope_status").value("VALID"))
                .andExpect(jsonPath("$.data.candidates[0].risk_score")
                        .value(org.hamcrest.Matchers.greaterThan(0.5)))
                .andExpect(jsonPath("$.data.memory_objects.length()").value(0));

        String negativeSignalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "NEGATIVE",
                "source_type", "USER_FEEDBACK",
                "signal_text", "正式文档，不要写废弃说明或历史演进。",
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
                .contains("promoted_from_candidate_id")
                .contains("policy_version")
                .contains("risk_score")
                .contains("scope_status");
    }

    @Test
    void memoryReviewQueueShouldApproveWeakCandidateAndAuditDecision() throws Exception {
        String workspaceId = createWorkspace();
        String weakSignalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "PREFERENCE",
                "source_type", "MODEL_INFERENCE",
                "signal_text", "系统推测用户偏好使用短句",
                "task_neighborhood", "CHAT_QA",
                "style_constraints", List.of("使用短句")
        ));
        MvcResult promotionResult = mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "signal_ids", List.of(weakSignalId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].review_status")
                        .value("NEEDS_REVIEW"))
                .andReturn();
        String candidateId = objectMapper.readTree(
                        promotionResult.getResponse().getContentAsString())
                .path("data").path("candidates").get(0)
                .path("candidate_id").asText();

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/reviews", workspaceId)
                        .param("kind", "CANDIDATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].review_kind").value("CANDIDATE"))
                .andExpect(jsonPath("$.data[0].review_id").value(candidateId))
                .andExpect(jsonPath("$.data[0].evidence_gate_status")
                        .value("NEEDS_REVIEW"));

        MvcResult decisionResult = decideMemoryReview(
                workspaceId, "CANDIDATE", candidateId, "APPROVE");
        JsonNode decision = objectMapper.readTree(
                decisionResult.getResponse().getContentAsString()).path("data");
        String promotedObjectId = decision.path("promoted_memory_object")
                .path("memory_object_id").asText();
        assertThat(promotedObjectId).isNotBlank();
        assertThat(decision.path("review_status").asText()).isEqualTo("PROMOTED");

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/control-pack/chat", workspaceId)
                        .param("answer_mode", "QA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.style_constraints[0]").value("使用短句"))
                .andExpect(jsonPath("$.data.memory_object_ids[0]")
                        .value(promotedObjectId));

        String rejectedSignalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "PREFERENCE",
                "source_type", "MODEL_INFERENCE",
                "signal_text", "系统推测用户偏好使用超长段落",
                "task_neighborhood", "CHAT_WIKI",
                "style_constraints", List.of("使用超长段落")
        ));
        MvcResult rejectedPromotion = mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "signal_ids", List.of(rejectedSignalId)))))
                .andExpect(status().isOk())
                .andReturn();
        String rejectedCandidateId = objectMapper.readTree(
                        rejectedPromotion.getResponse().getContentAsString())
                .path("data").path("candidates").get(0)
                .path("candidate_id").asText();
        decideMemoryReview(
                workspaceId, "CANDIDATE", rejectedCandidateId, "REJECT");
        assertThat(jdbcTemplate.queryForObject("""
                select review_status from memory_candidate
                where workspace_id = ? and id = ?
                """, String.class, workspaceId, rejectedCandidateId))
                .isEqualTo("REJECTED");

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/reviews", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from memory_review_decision
                where workspace_id = ? and review_kind = 'CANDIDATE'
                  and review_id = ? and decision = 'APPROVE'
                """, Integer.class, workspaceId, candidateId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from memory_review_decision
                where workspace_id = ? and review_kind = 'CANDIDATE'
                """, Integer.class, workspaceId)).isEqualTo(2);
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
        MvcResult positivePromotionResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("signal_ids", List.of(positiveSignalId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memory_objects.length()").value(1))
                .andReturn();
        String existingObjectId = objectMapper.readTree(
                        positivePromotionResult.getResponse().getContentAsString())
                .path("data").path("memory_objects").get(0)
                .path("memory_object_id").asText();

        String conflictingNegativeSignalId = createMemorySignal(workspaceId, Map.of(
                "signal_type", "NEGATIVE",
                "source_type", "USER_FEEDBACK",
                "signal_text", "正式文档统一使用先结论后结构",
                "task_neighborhood", "CHAT_QA",
                "forbidden_patterns", List.of("先结论后结构")
        ));
        MvcResult conflictResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("signal_ids", List.of(conflictingNegativeSignalId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].conflict_status").value("CONFLICTING_ACTIVE_MEMORY"))
                .andExpect(jsonPath("$.data.candidates[0].review_status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.data.memory_objects.length()").value(0))
                .andReturn();
        String conflictingCandidateId = objectMapper.readTree(
                        conflictResult.getResponse().getContentAsString())
                .path("data").path("candidates").get(0)
                .path("candidate_id").asText();

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/reviews", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].review_id").value(conflictingCandidateId))
                .andExpect(jsonPath("$.data[0].priority").value(100));

        mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/memory/reviews/CANDIDATE/{candidateId}/decisions",
                        workspaceId, conflictingCandidateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decision", "APPROVE"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("MEMORY_REVIEW_CONFLICT_RESOLUTION_REQUIRED"));

        MvcResult replaceResult = decideMemoryReview(
                workspaceId, "CANDIDATE", conflictingCandidateId, "REPLACE_EXISTING");
        JsonNode replacement = objectMapper.readTree(
                replaceResult.getResponse().getContentAsString()).path("data");
        String replacementObjectId = replacement.path("promoted_memory_object")
                .path("memory_object_id").asText();
        assertThat(replacement.path("revoked_memory_object_ids").toString())
                .contains(existingObjectId);
        assertThat(replacementObjectId).isNotEqualTo(existingObjectId);

        Integer activeMemoryCount = jdbcTemplate.queryForObject(
                "select count(*) from memory_object where workspace_id = ? and status = 'ACTIVE'",
                Integer.class,
                workspaceId
        );
        assertThat(activeMemoryCount).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select status from memory_object where workspace_id = ? and id = ?
                """, String.class, workspaceId, existingObjectId)).isEqualTo("REVOKED");
        assertThat(jdbcTemplate.queryForObject("""
                select memory_type from memory_object where workspace_id = ? and id = ?
                """, String.class, workspaceId, replacementObjectId)).isEqualTo("NEGATIVE");

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/memory/control-pack/chat", workspaceId)
                        .param("answer_mode", "QA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.forbidden_patterns[0]")
                        .value("先结论后结构"));
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
        String workspaceId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("workspace_id").asText();
        jdbcTemplate.update(
                "update workspace set retrieval_strategy_v2_enabled = true where id = ?",
                workspaceId);
        return workspaceId;
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

    private String uploadSource(String workspaceId, String fileName, String content) throws Exception {
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
                .andExpect(jsonPath("$.data.parse_status").value(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.equalTo("PARSED"), org.hamcrest.Matchers.equalTo("PARSING_QUEUED"))))
                .andExpect(jsonPath("$.data.index_status").value(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.equalTo("INDEXED"), org.hamcrest.Matchers.equalTo("INDEX_QUEUED"))));
        MvcResult sources = mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/sources", workspaceId))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode source : objectMapper.readTree(
                sources.getResponse().getContentAsString()).path("data")) {
            if (fileName.equals(source.path("title").asText())) {
                return source.path("source_id").asText();
            }
        }
        throw new AssertionError("Uploaded source was not listed: " + fileName);
    }

    private String createMemorySignal(String workspaceId, Map<String, Object> payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/signals", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("signal_id").asText();
    }

    private MvcResult recordMemoryOutcome(
            String workspaceId,
            String targetId,
            String outcomeType
    ) throws Exception {
        return mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/outcomes", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "target_type", "CONVERSATION_MESSAGE",
                                "target_id", targetId,
                                "outcome_type", outcomeType
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policy_version")
                        .value("memory-outcome-policy-v1"))
                .andReturn();
    }

    private MvcResult decideMemoryReview(
            String workspaceId,
            String reviewKind,
            String reviewId,
            String decision
    ) throws Exception {
        return mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/memory/reviews/{reviewKind}/{reviewId}/decisions",
                        workspaceId, reviewKind, reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decision", decision,
                                "reason", "contract test review decision"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review_decision_id").isNotEmpty())
                .andExpect(jsonPath("$.data.decision").value(decision))
                .andReturn();
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

    private JsonNode sendCompletedMessage(
            String conversationId,
            String answerMode,
            String content
    ) throws Exception {
        JsonNode response = sendMessage(conversationId, answerMode, content);
        ChatStreamTestSupport.perform(
                        mockMvc,
                        response.path("data").path("assistant_request_id").asText())
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("event:chat.completed")));
        return response;
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
