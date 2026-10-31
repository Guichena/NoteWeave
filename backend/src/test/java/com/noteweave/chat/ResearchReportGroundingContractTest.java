package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import java.util.LinkedHashMap;
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

/**
 * REPORT 通道契约：问答可以依据对话里已完成的研究报告作答，证据是报告实际引用过的网页原文，
 * 引用按研究运行编号和证据键落库；只用 REPORT 通道时不需要工作台资料，也不访问搜索引擎。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResearchReportGroundingContractTest {

    private static final String KEY_ORDERING = "a1b2c3d4e5f60718";
    private static final String KEY_REBALANCE = "b1b2c3d4e5f60718";
    private static final String KEY_AUDIT_ONLY = "c1b2c3d4e5f60718";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void followUpCitesTheVerifiedQuotesOfTheConversationsResearchReport() throws Exception {
        String workspace = workspace();
        String conversation = conversation(workspace);
        String researchRunId = completedResearch(conversation, "[]");

        JsonNode receipt = submit(conversation, "Kafka 的分区顺序在重平衡时会怎样？", "QA",
                Map.of("retrieval_strategy", "AUTO", "retrieval_channels", List.of("REPORT"),
                        "grounding_refs", List.of(researchRunId)), 200);
        String runId = receipt.path("answer_run_id").asText();
        String assistantMessageId = receipt.path("assistant_message_id").asText();

        // 只取报告正文引用过的证据，引用核对小节里才出现的证据键不参与；与问题词项重合多的排在前面
        List<Map<String, Object>> manifest = jdbc.queryForList("""
                select evidence_id, evidence_kind, passage_id, location_info, excerpt
                from answer_run_evidence_manifest where answer_run_id = ? order by rank_no
                """, runId);
        assertThat(manifest).extracting(row -> row.get("evidence_kind"))
                .containsOnly("RESEARCH_EVIDENCE");
        assertThat(manifest).extracting(row -> row.get("passage_id"))
                .containsExactly(KEY_REBALANCE, KEY_ORDERING);
        assertThat(manifest.get(0).get("evidence_id"))
                .isEqualTo("research-evidence:" + researchRunId + ":" + KEY_REBALANCE);
        assertThat(manifest.get(0).get("location_info")).isEqualTo("https://kafka.apache.org/rebalance");
        assertThat((String) manifest.get(0).get("excerpt")).contains("报告结论：", "网页原文：");

        // 研究引用写进 citation，顺序与证据编号一致，回答里的 [n] 才能对上第 n 张引用卡片
        List<Map<String, Object>> citations = jdbc.queryForList("""
                select c.source_id, c.research_run_id, c.research_evidence_key, c.location_info, c.quote_text
                from message_citation mc join citation c on c.id = mc.citation_id
                where mc.message_id = ? order by mc.sort_order
                """, assistantMessageId);
        assertThat(citations).extracting(row -> row.get("research_evidence_key"))
                .containsExactly(KEY_REBALANCE, KEY_ORDERING);
        assertThat(citations).allSatisfy(row -> {
            assertThat(row.get("source_id")).isNull();
            assertThat(row.get("research_run_id")).isEqualTo(researchRunId);
        });
        assertThat((String) citations.get(0).get("quote_text")).doesNotContain("报告结论");

        String planVersion = jdbc.queryForObject(
                "select retrieval_plan_version from answer_run where id = ?", String.class, runId);
        assertThat(planVersion).endsWith("+research-report-v1");

        // 保存为笔记时研究引用按所属研究报告的依赖校验，不能当成资料已撤销
        JsonNode note = data(post("/api/v2/workspaces/{workspaceId}/knowledge-items", workspace)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("item_type", "NOTE", "title", "重平衡笔记",
                        "content", "重平衡时分区重新分配 [1]。", "source_message_id", assistantMessageId))), 200);
        assertThat(jdbc.queryForObject("""
                select count(*) from knowledge_version_citation kvc
                join knowledge_item i on i.latest_version_id = kvc.knowledge_version_id
                where i.id = ?
                """, Integer.class, note.path("item_id").asText())).isEqualTo(2);
    }

    @Test
    void autoReportUsesTheConversationsCompletedResearchUntilItsTurnIsDeleted() throws Exception {
        String workspace = workspace();
        String conversation = conversation(workspace);
        String researchRunId = completedResearch(conversation, "[]");

        JsonNode receipt = submit(conversation, "分区内的顺序怎么保证？", "QA",
                Map.of("retrieval_strategy", "AUTO", "retrieval_channels", List.of("REPORT")), 200);
        assertThat(jdbc.queryForList("""
                select passage_id from answer_run_evidence_manifest where answer_run_id = ?
                """, String.class, receipt.path("answer_run_id").asText())).contains(KEY_ORDERING);

        String researchTurnMessage = jdbc.queryForObject(
                "select answer_message_id from research_run where id = ?", String.class, researchRunId);
        mvc.perform(delete("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages/{messageId}",
                workspace, conversation, researchTurnMessage)).andExpect(status().isOk());

        JsonNode rejected = submit(conversation, "分区内的顺序怎么保证？", "QA",
                Map.of("retrieval_strategy", "AUTO", "retrieval_channels", List.of("REPORT")), 409);
        assertThat(rejected.path("code").asText()).isEqualTo("RESEARCH_REPORT_NOT_FOUND");
    }

    @Test
    void unusableOrUnfinishedReportsAreSkippedWithAnExplicitReason() throws Exception {
        String workspace = workspace();
        String conversation = conversation(workspace);
        // 声明范围里的资料不存在：依赖闭包不通过，整份报告不能作为当前证据
        String revoked = completedResearch(conversation, "[\"" + Ids.newId() + "\"]");

        JsonNode receipt = submit(conversation, "分区内的顺序怎么保证？", "QA",
                Map.of("retrieval_strategy", "AUTO", "retrieval_channels", List.of("REPORT"),
                        "grounding_refs", List.of(revoked)), 200);
        assertThat(degradationOf(receipt)).contains("RESEARCH_REPORT_NOT_USABLE");
        assertThat(jdbc.queryForObject("""
                select count(*) from answer_run_evidence_manifest where answer_run_id = ?
                """, Integer.class, receipt.path("answer_run_id").asText())).isZero();

        String running = researchTurn(conversation);
        JsonNode pending = submit(conversation, "分区内的顺序怎么保证？", "QA",
                Map.of("retrieval_strategy", "AUTO", "retrieval_channels", List.of("REPORT"),
                        "grounding_refs", List.of(running)), 200);
        assertThat(degradationOf(pending)).contains("RESEARCH_REPORT_NOT_COMPLETED");
    }

    // 回执在检索前生成，降级原因随证据快照保存在回答运行上
    private String degradationOf(JsonNode receipt) {
        return jdbc.queryForObject("select evidence_bundle_json from answer_run where id = ?",
                String.class, receipt.path("answer_run_id").asText());
    }

    @Test
    void reportGroundingRejectsForeignRefsAndNonQaModes() throws Exception {
        String workspace = workspace();
        String conversation = conversation(workspace);
        String researchRunId = completedResearch(conversation, "[]");

        JsonNode foreign = submit(conversation, "顺序", "QA",
                Map.of("retrieval_strategy", "AUTO", "retrieval_channels", List.of("REPORT"),
                        "grounding_refs", List.of(Ids.newId())), 404);
        assertThat(foreign.path("code").asText()).isEqualTo("RETRIEVAL_GROUNDING_REF_INVALID");

        JsonNode note = submit(conversation, "顺序", "NOTE",
                Map.of("retrieval_strategy", "AUTO", "retrieval_channels", List.of("REPORT"),
                        "grounding_refs", List.of(researchRunId)), 409);
        assertThat(note.path("code").asText()).isEqualTo("RETRIEVAL_CHANNEL_UNSUPPORTED");

        JsonNode web = submit(conversation, "顺序", "QA",
                Map.of("retrieval_strategy", "AUTO", "retrieval_channels", List.of("WEB")), 409);
        assertThat(web.path("code").asText()).isEqualTo("RETRIEVAL_CHANNEL_UNSUPPORTED");
    }

    /** 走正式的深度研究回合创建研究运行，再按完成后的样子写入报告和证据。 */
    private String completedResearch(String conversation, String sourceScopeJson) throws Exception {
        String researchRunId = researchTurn(conversation);
        String markdown = String.join("\n",
                "# Kafka 与 Pulsar 的顺序保证",
                "",
                "Kafka 只在单个分区内保证顺序 [evidence:" + KEY_ORDERING + "]。",
                "",
                "## 直接回答",
                "",
                "消费者组重平衡期间分区会重新分配，未提交的位移可能导致重复消费 [evidence:" + KEY_REBALANCE + "]。",
                "",
                "## Citation audit",
                "",
                "### [3] 只在核对小节出现",
                "",
                "- Evidence: `evidence:" + KEY_AUDIT_ONLY + "`");
        jdbc.update("""
                update research_run
                set status = 'COMPLETED', final_report_title = ?, final_report_markdown = ?, source_scope_json = ?
                where id = ?
                """, "Kafka 与 Pulsar 的顺序保证", markdown, sourceScopeJson, researchRunId);
        evidence(researchRunId, KEY_ORDERING, "Kafka 文档：分区顺序", "https://kafka.apache.org/ordering",
                "Kafka only provides a total order over records within a partition.",
                "Kafka 只在单个分区内保证顺序");
        evidence(researchRunId, KEY_REBALANCE, "Kafka 文档：重平衡", "https://kafka.apache.org/rebalance",
                "During a rebalance, partitions are reassigned and uncommitted offsets may be consumed again.",
                "重平衡时分区重新分配，可能重复消费");
        evidence(researchRunId, KEY_AUDIT_ONLY, "无关页面", "https://example.com/unrelated",
                "Unrelated text.", "无关结论");
        return researchRunId;
    }

    private String researchTurn(String conversation) throws Exception {
        JsonNode receipt = submit(conversation, "Kafka 与 Pulsar 的消息顺序保证有什么不同？",
                "DEEP_RESEARCH", Map.of(), 200);
        return receipt.path("research_run_id").asText();
    }

    private void evidence(String researchRunId, String key, String title, String url, String quote, String claim) {
        jdbc.update("""
                insert into source_evidence(
                    id, research_run_id, evidence_key, source_title, source_url, quote_text, claim_text,
                    snapshot_status
                ) values (?, ?, ?, ?, ?, ?, ?, 'EXTERNAL_ARCHIVED')
                """, Ids.newId(), researchRunId, key, title, url, quote, claim);
    }

    private String workspace() throws Exception {
        return data(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("name", "report-grounding-" + System.nanoTime(),
                        "description", "REPORT channel contract"))), 200)
                .path("workspace_id").asText();
    }

    private String conversation(String workspace) throws Exception {
        return data(post("/api/v2/workspaces/{workspaceId}/conversations", workspace)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("title", "Report grounding",
                        "conversation_type", "WORKSPACE_CHAT"))), 200)
                .path("conversation_id").asText();
    }

    private JsonNode submit(String conversation, String input, String mode, Map<String, Object> retrieval,
                            int expectedStatus) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", input);
        body.put("answer_mode", mode);
        body.put("client_request_id", "report-grounding-" + System.nanoTime());
        body.putAll(retrieval);
        return data(post("/api/v2/conversations/{conversationId}/messages", conversation)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(body)), expectedStatus);
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          int expectedStatus) throws Exception {
        JsonNode root = mapper.readTree(mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString());
        return expectedStatus == 200 ? root.path("data") : root;
    }
}
