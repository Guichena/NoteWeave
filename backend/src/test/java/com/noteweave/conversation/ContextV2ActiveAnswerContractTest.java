package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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

@SpringBootTest(properties = "noteweave.context.v2.active-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContextV2ActiveAnswerContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @SpyBean ConversationContextCompilerV2Service compiler;
    @Autowired ContextV2ShadowSnapshotService frozenSnapshots;

    @Test
    void qaNoteAndWikiConsumeTheProjectionFrozenBeforeGeneration() throws Exception {
        String workspace = workspace();
        JsonNode rollout = data(put("/api/v2/workspaces/{workspaceId}/context-v2-rollout", workspace)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("mode", "ACTIVE"))));
        assertThat(rollout.path("active_effective").asBoolean()).isTrue();
        for (String mode : new String[] {"QA", "NOTE", "WIKI"}) {
            String conversation = conversation(workspace, mode);
            String input = "请解释缓存一致性 " + mode;
            JsonNode receipt = submit(conversation, input, mode);
            String runId = receipt.path("answer_run_id").asText();
            var snapshot = jdbc.queryForMap("""
                    select compiler_version, snapshot_json, token_budget_json
                    from run_input_snapshot where answer_run_id = ?
                    """, runId);
            assertThat(snapshot.get("compiler_version"))
                    .isEqualTo(ContextWindowPlannerV2.COMPILER_VERSION);
            JsonNode frozenInput = mapper.readTree((String) snapshot.get("snapshot_json"));
            String shadowId = frozenInput.path("context_v2_snapshot_id").asText();
            assertThat(shadowId).isNotBlank();
            assertThat(frozenInput.path("context_v2_raw_message_refs").get(0)
                    .path("message_id").asText()).isEqualTo(receipt.path("message_id").asText());
            assertThat(frozenInput.path("context_v2_projection_sha256").asText())
                    .isEqualTo(jdbc.queryForObject("""
                            select projection_sha256 from context_v2_shadow_snapshot where id = ?
                            """, String.class, shadowId));
            assertThat(mapper.readTree((String) snapshot.get("token_budget_json"))
                    .path("context_v2_budget_bytes").asInt()).isGreaterThan(0);
            assertThat(jdbc.queryForObject("""
                    select status from context_v2_shadow_snapshot where id = ?
                    """, String.class, shadowId)).isEqualTo("READY");
        }
    }

    @Test
    void compilationFailureRecordsReasonAndUsesV1ForTheAnswer() throws Exception {
        String workspace = workspace();
        data(put("/api/v2/workspaces/{workspaceId}/context-v2-rollout", workspace)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("mode", "ACTIVE"))));
        String conversation = conversation(workspace, "fallback");
        doThrow(new BusinessException("CONTEXT_BUDGET_EXCEEDED", "fixed test failure"))
                .when(compiler).compile(anyString(), anyString(), anyString(), anyInt(),
                        anyString(), anyString(), anyInt());
        JsonNode receipt = submit(conversation, "当前问题", "QA");
        var snapshot = jdbc.queryForMap("""
                select compiler_version, snapshot_json from run_input_snapshot where answer_run_id = ?
                """, receipt.path("answer_run_id").asText());
        assertThat(snapshot.get("compiler_version")).isEqualTo("segment-projection-v1");
        assertThat(mapper.readTree((String) snapshot.get("snapshot_json"))
                .path("context_v2_fallback_code").asText()).isEqualTo("CONTEXT_BUDGET_EXCEEDED");
        assertThat(jdbc.queryForObject("""
                select status from context_v2_shadow_snapshot where answer_run_id = ?
                """, String.class, receipt.path("answer_run_id").asText())).isEqualTo("FAILED");
    }

    @Test
    void disablingActivePreservesOldRunAndDeletionRedactsItsFrozenProjection() throws Exception {
        String workspace = workspace();
        data(put("/api/v2/workspaces/{workspaceId}/context-v2-rollout", workspace)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("mode", "ACTIVE"))));
        String conversation = conversation(workspace, "rollback");
        String privateText = "私有上下文-" + System.nanoTime();
        JsonNode active = submit(conversation, privateText, "QA");
        String activeRun = active.path("answer_run_id").asText();
        String shadowId = mapper.readTree((String) jdbc.queryForMap("""
                select snapshot_json from run_input_snapshot where answer_run_id = ?
                """, activeRun).get("snapshot_json"))
                .path("context_v2_snapshot_id").asText();

        JsonNode off = data(put("/api/v2/workspaces/{workspaceId}/context-v2-rollout", workspace)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("mode", "OFF"))));
        assertThat(off.path("active_effective").asBoolean()).isFalse();
        JsonNode later = submit(conversation(workspace, "after-off"), "普通问题", "QA");
        assertThat(jdbc.queryForMap("""
                select compiler_version from run_input_snapshot where answer_run_id = ?
                """, later.path("answer_run_id").asText()).get("compiler_version"))
                .isEqualTo("segment-projection-v1");
        assertThat(jdbc.queryForObject("""
                select count(*) from context_v2_shadow_snapshot where answer_run_id = ?
                """, Integer.class, later.path("answer_run_id").asText())).isZero();
        assertThat(jdbc.queryForObject("""
                select status from context_v2_shadow_snapshot where id = ?
                """, String.class, shadowId)).isEqualTo("READY");

        mvc.perform(delete("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages/{messageId}",
                workspace, conversation, active.path("message_id").asText()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                select status from context_v2_shadow_snapshot where id = ?
                """, String.class, shadowId)).isEqualTo("REDACTED");
        assertThat(jdbc.queryForObject("""
                select projection_json from context_v2_shadow_snapshot where id = ?
                """, String.class, shadowId)).doesNotContain(privateText);
        assertThat(jdbc.queryForObject("""
                select replay_availability from run_input_snapshot where answer_run_id = ?
                """, String.class, activeRun)).isEqualTo("METADATA_ONLY");
    }

    @Test
    void malformedProjectionWithMatchingDigestIsRejectedAsCorrupt() throws Exception {
        String workspace = workspace();
        data(put("/api/v2/workspaces/{workspaceId}/context-v2-rollout", workspace)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("mode", "ACTIVE"))));
        String conversation = conversation(workspace, "corrupt");
        JsonNode receipt = submit(conversation, "问题", "QA");
        String invalidJson = "{}";
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(invalidJson.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("""
                update context_v2_shadow_snapshot
                set projection_json = ?, projection_sha256 = ? where answer_run_id = ?
                """, invalidJson, digest, receipt.path("answer_run_id").asText());
        assertThatThrownBy(() -> frozenSnapshots.readReadyForAnswer(workspace, conversation,
                receipt.path("message_id").asText()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CONTEXT_V2_SNAPSHOT_CORRUPT"));
    }

    @Test
    void answerActiveGateDoesNotEnableResearchConsumption() throws Exception {
        String workspace = workspace();
        data(put("/api/v2/workspaces/{workspaceId}/context-v2-rollout", workspace)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("mode", "ACTIVE"))));
        JsonNode receipt = submit(conversation(workspace, "research-gate"),
                "研究缓存一致性", "DEEP_RESEARCH");
        String runId = receipt.path("research_run_id").asText();
        assertThat(jdbc.queryForObject("""
                select compiler_version from run_input_snapshot where research_run_id = ?
                """, String.class, runId)).isEqualTo("research-input-v1");
        assertThat(jdbc.queryForObject("""
                select count(*) from research_run
                where id = ? and execution_question is null and context_snapshot_id is null
                """, Integer.class, runId)).isEqualTo(1);
    }

    private String workspace() throws Exception {
        return data(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("name", "context-active-" + System.nanoTime(),
                        "description", "Context v2 active contract"))))
                .path("workspace_id").asText();
    }

    private String conversation(String workspace, String label) throws Exception {
        return data(post("/api/v2/workspaces/{workspaceId}/conversations", workspace)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("title", "active-" + label,
                        "conversation_type", "WORKSPACE_CHAT"))))
                .path("conversation_id").asText();
    }

    private JsonNode submit(String conversation, String input, String mode) throws Exception {
        return data(post("/api/v2/conversations/{conversationId}/messages", conversation)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("content", input, "answer_mode", mode,
                        "client_request_id", "active-" + mode + "-" + System.nanoTime()))));
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }
}
