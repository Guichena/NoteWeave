package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    @SpyBean ConversationContextCompilerV2Service compiler;

    @Test
    void answerFreezesInputBeforeAssistantAndDeletionClearsShadowText() throws Exception {
        String workspaceId = data(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "shadow-freeze",
                        "description", "v2 frozen input")))).path("workspace_id").asText();
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
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    private String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
