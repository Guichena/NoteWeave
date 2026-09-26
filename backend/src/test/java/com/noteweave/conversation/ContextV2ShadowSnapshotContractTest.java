package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.memory.ExecutionObservation;
import com.noteweave.memory.MemoryRuntime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "noteweave.context.v2.shadow-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContextV2ShadowSnapshotContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryRuntime memoryRuntime;
    @Autowired ConversationTopicProjectionV2Service topics;
    @Autowired ConversationTopicSummaryV2Service summaries;
    @SpyBean ConversationContextCompilerV2Service compiler;

    @Test
    void workspaceModeCanOptIntoShadowAndTurnItOffWithoutEnablingActive() throws Exception {
        String workspaceId = data(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "shadow-rollout",
                        "description", "workspace switch")))).path("workspace_id").asText();
        JsonNode initial = data(get("/api/v2/workspaces/{workspaceId}/context-v2-rollout", workspaceId));
        assertThat(initial.path("mode").asText()).isEqualTo("OFF");
        assertThat(initial.path("shadow_effective").asBoolean()).isFalse();
        String conversationId = data(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("title", "shadow-rollout",
                        "conversation_type", "WORKSPACE_CHAT")))).path("conversation_id").asText();
        JsonNode off = data(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("content", "Mode off",
                        "answer_mode", "QA", "client_request_id", "rollout-off-1"))));
        assertThat(shadowCount(off.path("answer_run_id").asText())).isZero();
        assertThat(data(get("/api/v2/workspaces/{workspaceId}/context-v2-rollout/runs/{runId}/diff",
                workspaceId, off.path("answer_run_id").asText()))
                .path("gap_code").asText()).isEqualTo("SHADOW_NOT_RECORDED");

        enableShadow(workspaceId);
        JsonNode on = data(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("content", "Mode shadow",
                        "answer_mode", "QA", "client_request_id", "rollout-on-2"))));
        assertThat(shadowCount(on.path("answer_run_id").asText())).isEqualTo(1);

        JsonNode disabled = data(put("/api/v2/workspaces/{workspaceId}/context-v2-rollout", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("mode", "OFF"))));
        assertThat(disabled.path("shadow_effective").asBoolean()).isFalse();
        JsonNode after = data(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("content", "Mode off again",
                        "answer_mode", "QA", "client_request_id", "rollout-off-3"))));
        assertThat(shadowCount(after.path("answer_run_id").asText())).isZero();
        mvc.perform(put("/api/v2/workspaces/{workspaceId}/context-v2-rollout", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("mode", "ACTIVE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONTEXT_V2_ROLLOUT_MODE_INVALID"));
    }

    @Test
    void answerFreezesInputBeforeAssistantAndDeletionClearsShadowText() throws Exception {
        String workspaceId = data(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "shadow-freeze",
                        "description", "v2 frozen input")))).path("workspace_id").asText();
        enableShadow(workspaceId);
        String conversationId = data(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("title", "shadow-freeze",
                        "conversation_type", "WORKSPACE_CHAT")))).path("conversation_id").asText();
        String privateText = "Private frozen context sentinel";
        JsonNode receipt = data(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("content", privateText,
                        "answer_mode", "QA", "client_request_id", "shadow-freeze-1"))));
        String runId = receipt.path("answer_run_id").asText();
        String queryId = receipt.path("message_id").asText();
        String assistantId = receipt.path("assistant_message_id").asText();
        String snapshotId = jdbc.queryForObject("""
                select id from context_v2_shadow_snapshot
                where workspace_id = ? and answer_run_id = ? and status = 'READY'
                """, String.class, workspaceId, runId);
        String json = jdbc.queryForObject("""
                select projection_json from context_v2_shadow_snapshot where id = ?
                """, String.class, snapshotId);
        ContextProjectionV2 frozen = mapper.readValue(json, ContextProjectionV2.class);
        assertThat(frozen.cutoffSeq()).isEqualTo(1);
        assertThat(frozen.currentInput()).isEqualTo(privateText);
        assertThat(frozen.rawTail()).extracting(ContextProjectionV2.RawMessage::messageId)
                .containsExactly(queryId).doesNotContain(assistantId);
        assertThat(jdbc.queryForObject("""
                select projection_sha256 from context_v2_shadow_snapshot where id = ?
                """, String.class, snapshotId)).isEqualTo(sha256(json));
        assertThat(jdbc.queryForObject("""
                select count(*) from context_v2_shadow_ref
                where snapshot_id = ? and ref_type = 'MESSAGE' and ref_id = ?
                """, Integer.class, snapshotId, queryId)).isEqualTo(1);
        JsonNode diff = data(get("/api/v2/workspaces/{workspaceId}/context-v2-rollout/runs/{runId}/diff",
                workspaceId, runId));
        assertThat(diff.path("shadow_status").asText()).isEqualTo("READY");
        assertThat(diff.path("v1_compiler_version").asText()).isEqualTo("segment-projection-v1");
        assertThat(diff.path("v2_compiler_version").asText()).isEqualTo("context-window-v2-shadow-a1");
        assertThat(diff.path("messages").path("only_v2").toString()).contains(queryId);
        assertThat(diff.toString()).doesNotContain(privateText);
        jdbc.update("""
                update context_v2_shadow_snapshot set projection_sha256 = ? where id = ?
                """, "0".repeat(64), snapshotId);
        mvc.perform(get("/api/v2/workspaces/{workspaceId}/context-v2-rollout/runs/{runId}/diff",
                        workspaceId, runId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTEXT_V2_SHADOW_CORRUPT"));
        jdbc.update("""
                update context_v2_shadow_snapshot set projection_sha256 = ? where id = ?
                """, sha256(json), snapshotId);

        mvc.perform(delete("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages/{messageId}",
                workspaceId, conversationId, queryId)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("""
                select status from context_v2_shadow_snapshot where id = ?
                """, String.class, snapshotId)).isEqualTo("REDACTED");
        String redactedJson = jdbc.queryForObject("""
                select projection_json from context_v2_shadow_snapshot where id = ?
                """, String.class, snapshotId);
        assertThat(redactedJson).doesNotContain(privateText);
        ContextProjectionV2 redacted = mapper.readValue(redactedJson, ContextProjectionV2.class);
        assertThat(redacted.replayAvailability()).isEqualTo("METADATA_ONLY");
        assertThat(redacted.currentInput()).isEmpty();
        assertThat(redacted.rawTail().get(0).text()).isEmpty();
        assertThat(data(get("/api/v2/workspaces/{workspaceId}/context-v2-rollout/runs/{runId}/diff",
                workspaceId, runId)).path("shadow_status").asText()).isEqualTo("REDACTED");
        assertThat(jdbc.queryForObject("""
                select projection_sha256 from context_v2_shadow_snapshot where id = ?
                """, String.class, snapshotId)).isEqualTo(sha256(redactedJson));
    }

    @Test
    void shadowCompileFailureRecordsGapWithoutFailingTheAnswer() throws Exception {
        String workspaceId = data(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "shadow-gap",
                        "description", "fallback contract")))).path("workspace_id").asText();
        enableShadow(workspaceId);
        String conversationId = data(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("title", "shadow-gap",
                        "conversation_type", "WORKSPACE_CHAT")))).path("conversation_id").asText();
        doThrow(new com.noteweave.common.BusinessException("CONTEXT_BUDGET_EXCEEDED", "test gap"))
                .when(compiler).compile(anyString(), anyString(), anyString(), anyInt(),
                        anyString(), anyString(), anyInt());

        JsonNode receipt = data(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("content", "Answer still works",
                        "answer_mode", "QA", "client_request_id", "shadow-gap-1"))));

        assertThat(receipt.path("answer_run_id").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("""
                select status from context_v2_shadow_snapshot where answer_run_id = ?
                """, String.class, receipt.path("answer_run_id").asText())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                select failure_code from context_v2_shadow_snapshot where answer_run_id = ?
                """, String.class, receipt.path("answer_run_id").asText()))
                .isEqualTo("CONTEXT_BUDGET_EXCEEDED");
        assertThat(jdbc.queryForObject("""
                select count(*) from run_input_snapshot where answer_run_id = ?
                """, Integer.class, receipt.path("answer_run_id").asText())).isEqualTo(1);
        assertThat(data(get("/api/v2/workspaces/{workspaceId}/context-v2-rollout/runs/{runId}/diff",
                workspaceId, receipt.path("answer_run_id").asText()))
                .path("gap_code").asText()).isEqualTo("CONTEXT_BUDGET_EXCEEDED");
    }

    @Test
    void revokingFrozenMemoryRevisionRedactsTheAnswerShadow() throws Exception {
        String workspaceId = data(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "shadow-memory-revoke",
                        "description", "revocation contract")))).path("workspace_id").asText();
        enableShadow(workspaceId);
        var proposal = memoryRuntime.observe(new ExecutionObservation(
                "shadow-revoke-" + System.nanoTime(), workspaceId, "WORKSPACE",
                "preference:shadow-revoke", "Private memory sentinel for shadow",
                "USER_FEEDBACK", "shadow-review"));
        data(post("/api/v2/workspaces/{workspaceId}/memory/revisions/{revisionId}/review",
                workspaceId, proposal.revisionId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("decision", "ACCEPT"))));
        String conversationId = data(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("title", "shadow-memory-revoke",
                        "conversation_type", "WORKSPACE_CHAT")))).path("conversation_id").asText();
        JsonNode receipt = data(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("content", "Use the approved preference",
                        "answer_mode", "QA", "client_request_id", "shadow-memory-revoke-1"))));
        String runId = receipt.path("answer_run_id").asText();
        String before = jdbc.queryForObject("""
                select projection_json from context_v2_shadow_snapshot where answer_run_id = ?
                """, String.class, runId);
        ContextProjectionV2 frozen = mapper.readValue(before, ContextProjectionV2.class);
        assertThat(frozen.memoryRevisions())
                .extracting(ContextProjectionV2.MemoryRevision::revisionId)
                .contains(proposal.revisionId());
        jdbc.update("""
                update memory_item set review_status = 'REVIEW_REQUIRED' where id = ?
                """, proposal.memoryItemId());

        data(post("/api/v2/workspaces/{workspaceId}/memory/revisions/{revisionId}/review",
                workspaceId, proposal.revisionId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("decision", "REVOKE"))));

        String after = jdbc.queryForObject("""
                select projection_json from context_v2_shadow_snapshot where answer_run_id = ?
                """, String.class, runId);
        assertThat(jdbc.queryForObject("""
                select status from context_v2_shadow_snapshot where answer_run_id = ?
                """, String.class, runId)).isEqualTo("REDACTED");
        assertThat(after).doesNotContain("Private memory sentinel for shadow");
        ContextProjectionV2 redacted = mapper.readValue(after, ContextProjectionV2.class);
        assertThat(redacted.memoryRevisions())
                .extracting(ContextProjectionV2.MemoryRevision::revisionId)
                .contains(proposal.revisionId());
        assertThat(redacted.memoryRevisions().get(0).text()).isEmpty();
    }

    @Test
    void deletingAnOldSummarizedMessageRedactsARecalledShadowRevision() throws Exception {
        String workspaceId = data(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "shadow-summary-delete",
                        "description", "summary redaction contract")))).path("workspace_id").asText();
        enableShadow(workspaceId);
        String conversationId = data(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("title", "shadow-summary-delete",
                        "conversation_type", "WORKSPACE_CHAT")))).path("conversation_id").asText();
        String firstMessageId = null;
        String[] turns = {"解释缓存一致性。", "重点是写入顺序。", "改聊台南旅行。",
                "住两晚。", "第一天看古迹。", "第二天吃小吃。"};
        for (int index = 0; index < turns.length; index++) {
            JsonNode receipt = data(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("content", turns[index],
                            "answer_mode", "QA", "client_request_id", "shadow-summary-" + index))));
            if (index == 0) firstMessageId = receipt.path("message_id").asText();
        }
        var projection = topics.refresh(workspaceId, conversationId);
        String firstSegmentId = projection.segments().get(0).segmentId();
        String revisionId = jdbc.queryForObject("""
                select id from conversation_topic_summary_revision_v2
                where segment_id = ? and end_seq = 4 and status = 'BUILDING'
                """, String.class, firstSegmentId);
        String summary = jdbc.query("""
                select role, content from conversation_message
                where conversation_id = ? and message_seq between 1 and 4 order by message_seq
                """, (rs, index) -> ConversationTopicSummaryV2Service.summarizeMessage(
                rs.getString(1), rs.getString(2)), conversationId).stream()
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        summaries.promote(firstSegmentId, revisionId,
                new PromoteSegmentSummaryRequest(summary, sha256(summary)));

        JsonNode recalled = data(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "content", "回到缓存一致性，第二种写入顺序呢？",
                        "answer_mode", "QA", "client_request_id", "shadow-summary-return"))));
        String runId = recalled.path("answer_run_id").asText();
        String frozenJson = jdbc.queryForObject("""
                select projection_json from context_v2_shadow_snapshot where answer_run_id = ?
                """, String.class, runId);
        ContextProjectionV2 frozen = mapper.readValue(frozenJson, ContextProjectionV2.class);
        assertThat(frozen.topicSummaries())
                .extracting(ContextProjectionV2.TopicSummary::revisionId).contains(revisionId);

        mvc.perform(delete("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages/{messageId}",
                workspaceId, conversationId, firstMessageId)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("""
                select status from conversation_topic_summary_revision_v2 where id = ?
                """, String.class, revisionId)).isEqualTo("STALE");
        String redactedJson = jdbc.queryForObject("""
                select projection_json from context_v2_shadow_snapshot where answer_run_id = ?
                """, String.class, runId);
        assertThat(jdbc.queryForObject("""
                select status from context_v2_shadow_snapshot where answer_run_id = ?
                """, String.class, runId)).isEqualTo("REDACTED");
        assertThat(redactedJson).doesNotContain(summary);
        assertThat(mapper.readValue(redactedJson, ContextProjectionV2.class)
                .topicSummaries().get(0).text()).isEmpty();
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    private void enableShadow(String workspaceId) throws Exception {
        JsonNode mode = data(put("/api/v2/workspaces/{workspaceId}/context-v2-rollout", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("mode", "SHADOW"))));
        assertThat(mode.path("mode").asText()).isEqualTo("SHADOW");
        assertThat(mode.path("shadow_effective").asBoolean()).isTrue();
    }

    private int shadowCount(String answerRunId) {
        return jdbc.queryForObject("""
                select count(*) from context_v2_shadow_snapshot where answer_run_id = ?
                """, Integer.class, answerRunId);
    }

    private String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
