package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemoryApplicationFeedbackContractTest {

    private static final String TOPIC = "noteweave.memory.extraction";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryConversationExtractionService extractionService;
    @Autowired MemoryApplicationFeedbackService feedbackService;
    @Autowired MemoryExtractionModelStub modelStub;

    /** 抽取只由模型决定，这里用离线模型桩给出确定输出，不发起任何网络调用。 */
    @TestConfiguration
    static class StubModelConfig {

        @Bean
        @Primary
        MemoryExtractionModelStub memoryExtractionModelStub() {
            Map<String, String> outputs = new LinkedHashMap<>();
            outputs.put("记住：以后回答要简短",
                    "[{\"statement\":\"以后回答要简短\",\"category\":\"style\",\"explicit\":true}]");
            outputs.put("记住：术语统一称为缓存",
                    "[{\"statement\":\"术语统一称为缓存\",\"category\":\"terminology\",\"explicit\":true}]");
            // 负面反馈由模型的 feedback 字段决定，不再看关键词
            outputs.put("太长了",
                    "[{\"statement\":\"太长了\",\"category\":\"style\",\"explicit\":false,\"feedback\":true}]");
            return new MemoryExtractionModelStub(outputs);
        }
    }

    @Test
    void ratingFollowsTheLastSubmissionAndContentReasonsDoNotChangeSlots() throws Exception {
        Prepared prepared = prepareInjectedAnswer();
        JsonNode tooLong = feedback(prepared.workspaceId(), prepared.runId(), "NEGATIVE", "太长了");
        assertThat(tooLong.path("aspect").asText()).isEqualTo("LENGTH");
        assertThat(tooLong.path("affected_memory_count").asInt()).isEqualTo(1);
        assertThat(utility(prepared.lengthId())).isCloseTo(0.5625, within(0.0001));
        assertThat(count(prepared.lengthId(), "negative_outcome_count")).isEqualTo(1);
        assertThat(text(prepared.lengthId(), "review_status")).isEqualTo("REVIEW_REQUIRED");
        assertThat(beta(prepared.lengthId())).isCloseTo(1.25, within(0.0001));
        assertThat(utility(prepared.termId())).isCloseTo(0.75, within(0.0001));
        assertThat(count(prepared.termId(), "negative_outcome_count")).isZero();
        assertThat(beta(prepared.termId())).isCloseTo(1, within(0.0001));

        JsonNode content = feedback(prepared.workspaceId(), prepared.runId(), "NEGATIVE", "内容不准确");
        assertThat(content.path("aspect").asText()).isEqualTo("CONTENT");
        assertThat(content.path("affected_memory_count").asInt()).isZero();
        assertThat(utility(prepared.lengthId())).isCloseTo(0.75, within(0.0001));
        assertThat(count(prepared.lengthId(), "negative_outcome_count")).isZero();
        assertThat(text(prepared.lengthId(), "review_status")).isEqualTo("APPROVED");
        assertThat(beta(prepared.lengthId())).isCloseTo(1, within(0.0001));

        JsonNode positive = feedback(prepared.workspaceId(), prepared.runId(), "POSITIVE", null);
        assertThat(positive.path("affected_memory_count").asInt()).isEqualTo(2);
        assertThat(utility(prepared.lengthId())).isCloseTo(0.8125, within(0.0001));
        assertThat(utility(prepared.termId())).isCloseTo(0.8125, within(0.0001));
        assertThat(count(prepared.lengthId(), "positive_outcome_count")).isEqualTo(1);
        assertThat(beta(prepared.lengthId())).isCloseTo(1, within(0.0001));

        feedback(prepared.workspaceId(), prepared.runId(), "POSITIVE", null);
        assertThat(count(prepared.lengthId(), "positive_outcome_count")).isEqualTo(1);
        assertThat(count(prepared.lengthId(), "application_count")).isEqualTo(1);
    }

    @Test
    void regeneratedFromRecordsRetryAndMissingRunDoesNotFailTheSend() throws Exception {
        Prepared prepared = prepareInjectedAnswer();
        send(prepared.conversationId(), "换一种说法", "retry-1", prepared.runId());
        assertThat(text(prepared.lengthId(), "review_status")).isEqualTo("APPROVED");
        assertThat(count(prepared.lengthId(), "retry_outcome_count")).isEqualTo(1);
        assertThat(utility(prepared.lengthId())).isCloseTo(0.6, within(0.0001));
        assertThat(jdbc.queryForObject("""
                select outcome_type from memory_usage_log
                where memory_item_id = ? and target_id = ?
                """, String.class, prepared.lengthId(), prepared.assistantMessageId())).isEqualTo("RETRIED");

        send(prepared.conversationId(), "再换一种", "retry-missing", Ids.newId());
        assertThat(count(prepared.lengthId(), "retry_outcome_count")).isEqualTo(1);

        String body = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}/feedback",
                        prepared.workspaceId(), Ids.newId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("rating", "NEGATIVE"))))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(mapper.readTree(body).path("code").asText()).isEqualTo("ANSWER_RUN_NOT_FOUND");
    }

    @Test
    void followUpAcceptsAtHalfWeightAndAComplaintOnlyHitsTheLengthSlot() throws Exception {
        Prepared prepared = prepareInjectedAnswer();
        String followUpId = send(prepared.conversationId(), "那一致性呢", "follow-1", null);
        consume(followUpId);
        assertThat(utility(prepared.lengthId())).isCloseTo(0.78125, within(0.0001));
        assertThat(utility(prepared.termId())).isCloseTo(0.78125, within(0.0001));
        assertThat(count(prepared.lengthId(), "positive_outcome_count")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select weight from memory_application_outcome
                where memory_item_id = ? and signal_kind = 'FOLLOW_UP' and superseded_at is null
                """, Double.class, prepared.lengthId())).isCloseTo(0.5, within(0.0001));

        String complaintId = send(prepared.conversationId(), "太长了", "follow-2", null);
        consume(complaintId);
        assertThat(utility(prepared.lengthId())).isCloseTo(0.5859, within(0.0001));
        assertThat(count(prepared.lengthId(), "negative_outcome_count")).isEqualTo(1);
        assertThat(text(prepared.lengthId(), "review_status")).isEqualTo("REVIEW_REQUIRED");
        assertThat(count(prepared.termId(), "negative_outcome_count")).isZero();
        assertThat(jdbc.queryForObject("""
                select aspect from memory_application_outcome
                where memory_item_id = ? and outcome_type = 'NEGATIVE' and superseded_at is null
                """, String.class, prepared.lengthId())).isEqualTo("LENGTH");
    }

    @Test
    void artifactEditUsesTheRatioAndBlankTextIsIgnored() throws Exception {
        Prepared prepared = prepareInjectedAnswer();
        String taskId = Ids.newId();
        ensureUsage(prepared.termId(), "ARTIFACT_JOB_RUN", taskId);
        int affected = feedbackService.recordArtifactEdit(
                prepared.workspaceId(), "ARTIFACT_JOB_RUN", taskId, "aaaa", "aaab");
        assertThat(affected).isEqualTo(1);
        assertThat(count(prepared.termId(), "edit_outcome_count")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select edit_magnitude from memory_application_outcome
                where memory_item_id = ? and signal_kind = 'EDIT' and superseded_at is null
                """, Double.class, prepared.termId())).isCloseTo(0.25, within(0.0001));

        assertThat(feedbackService.recordArtifactEdit(
                prepared.workspaceId(), "ARTIFACT_JOB_RUN", taskId, "aaaa", "aaab")).isEqualTo(1);
        assertThat(count(prepared.termId(), "edit_outcome_count")).isEqualTo(1);
        assertThat(feedbackService.recordArtifactEdit(
                prepared.workspaceId(), "ARTIFACT_JOB_RUN", Ids.newId(), " ", " ")).isZero();
    }

    @Test
    void disabledSwitchRecordsNothing() throws Exception {
        Prepared prepared = prepareInjectedAnswer();
        setEnabled(false);
        try {
            JsonNode body = feedback(prepared.workspaceId(), prepared.runId(), "NEGATIVE", "太长了");
            assertThat(body.path("degradation_reason").asText()).isEqualTo(MemoryApplicationFeedbackService.DISABLED);
            assertThat(body.path("affected_memory_count").asInt()).isZero();
            assertThat(utility(prepared.lengthId())).isCloseTo(0.75, within(0.0001));
            assertThat(jdbc.queryForObject("""
                    select count(*) from memory_application_outcome where workspace_id = ?
                    """, Integer.class, prepared.workspaceId())).isZero();
        } finally {
            setEnabled(true);
        }
    }

    private Prepared prepareInjectedAnswer() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        consume(send(conversationId, "记住：以后回答要简短", "mem-length", null));
        consume(send(conversationId, "记住：术语统一称为缓存", "mem-term", null));
        String lengthId = itemId(workspaceId, "answer.length");
        String termId = itemId(workspaceId, "terminology.term");
        assertThat(beta(lengthId)).isCloseTo(1, within(0.0001));
        assertThat(utility(lengthId)).isCloseTo(0.75, within(0.0001));
        JsonNode turn = sendTurn(conversationId, "解释一下缓存", "question-1", null);
        String assistantId = turn.path("assistant_message_id").asText();
        ensureUsage(lengthId, "CONVERSATION_MESSAGE", assistantId);
        ensureUsage(termId, "CONVERSATION_MESSAGE", assistantId);
        return new Prepared(workspaceId, conversationId, turn.path("answer_run_id").asText(),
                assistantId, lengthId, termId);
    }

    private void ensureUsage(String memoryItemId, String targetType, String targetId) {
        Integer existing = jdbc.queryForObject("""
                select count(*) from memory_usage_log
                where memory_item_id = ? and target_type = ? and target_id = ?
                """, Integer.class, memoryItemId, targetType, targetId);
        if (existing != null && existing > 0) {
            return;
        }
        jdbc.update("""
                insert into memory_usage_log(
                    id, memory_object_id, memory_version_id, memory_item_id, memory_revision_id,
                    workspace_id, task_type, target_type, target_id, compiled_as, outcome_policy_version
                )
                select ?, i.legacy_memory_object_id, null, i.id, i.current_revision_id, i.workspace_id,
                       'QA', ?, ?, 'CHAT', 'memory-outcome-policy-v1'
                from memory_item i
                where i.id = ?
                """, Ids.newId(), targetType, targetId, memoryItemId);
    }

    private String itemId(String workspaceId, String slot) {
        return jdbc.queryForObject("""
                select id from memory_item
                where workspace_id = ? and semantic_slot_key = ? and status = 'ACTIVE'
                """, String.class, workspaceId, slot);
    }

    private double utility(String memoryItemId) {
        return jdbc.queryForObject("select utility_score from memory_item where id = ?", Double.class, memoryItemId);
    }

    private double beta(String memoryItemId) {
        return jdbc.queryForObject("select evidence_beta from memory_item where id = ?", Double.class, memoryItemId);
    }

    private int count(String memoryItemId, String column) {
        return jdbc.queryForObject("select " + column + " from memory_item where id = ?", Integer.class, memoryItemId);
    }

    private String text(String memoryItemId, String column) {
        return jdbc.queryForObject("select " + column + " from memory_item where id = ?", String.class, memoryItemId);
    }

    private JsonNode feedback(String workspaceId, String runId, String rating, String reason) throws Exception {
        Map<String, String> body = reason == null ? Map.of("rating", rating) : Map.of("rating", rating, "reason", reason);
        return data(post("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}/feedback", workspaceId, runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    /** 只在抽取这一步打开模型桩：同一次请求里的回答生成仍然走模板回退。 */
    private void consume(String messageId) throws Exception {
        JsonNode payload = mapper.readTree(jdbc.queryForObject(
                "select payload_json from task_outbox where topic = ? and message_key = ?",
                String.class, TOPIC, messageId));
        modelStub.enable();
        try {
            extractionService.extract(payload.path("task_id").asText(), payload.path("workspace_id").asText(),
                    payload.path("message_id").asText(), payload.path("user_id").asText(),
                    payload.path("content").asText());
        } finally {
            modelStub.disable();
        }
    }

    private String send(String conversationId, String content, String clientRequestId, String regeneratedFrom)
            throws Exception {
        return sendTurn(conversationId, content, clientRequestId, regeneratedFrom).path("message_id").asText();
    }

    private JsonNode sendTurn(String conversationId, String content, String clientRequestId, String regeneratedFrom)
            throws Exception {
        Map<String, Object> body = regeneratedFrom == null
                ? Map.of("content", content, "answer_mode", "QA", "client_request_id", clientRequestId)
                : Map.of("content", content, "answer_mode", "QA", "client_request_id", clientRequestId,
                        "regenerated_from", regeneratedFrom);
        return data(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    private String createWorkspace() throws Exception {
        return data(post("/api/v2/workspaces").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "memory-feedback",
                        "description", "application outcome")))).path("workspace_id").asText();
    }

    private String createConversation(String workspaceId) throws Exception {
        return data(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("title", "memory feedback",
                        "conversation_type", "WORKSPACE_CHAT")))).path("conversation_id").asText();
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return mapper.readTree(body).path("data");
    }

    private void setEnabled(boolean enabled) throws Exception {
        Object target = feedbackService;
        while (true) {
            Object next = AopProxyUtils.getSingletonTarget(target);
            if (next == null) {
                break;
            }
            target = next;
        }
        Field field = MemoryApplicationFeedbackService.class.getDeclaredField("enabled");
        field.setAccessible(true);
        field.set(target, enabled);
    }

    private record Prepared(
            String workspaceId,
            String conversationId,
            String runId,
            String assistantMessageId,
            String lengthId,
            String termId
    ) {
    }
}
