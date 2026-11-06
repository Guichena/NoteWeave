package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.ConversationEventMux;
import com.noteweave.answer.ConversationLiveEvent;
import com.noteweave.support.DeterministicEmbeddingTestConfiguration;
import com.noteweave.support.RetrievalIndexCleanup;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 后台回答准备：发消息只创建回合与 PREPARING 运行就返回，准备在后台完成，
 * 过程中按阶段推送 answer.stage；准入检查失败时仍同步返回原错误码。
 */
@SpringBootTest(properties = {
        "noteweave.answer.async-preparation=true",
        "noteweave.context.v2.shadow-enabled=false",
        "noteweave.context.v2.active-enabled=false",
        "noteweave.context.v2.default-mode=OFF"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "test-es"})
@Import(DeterministicEmbeddingTestConfiguration.class)
class AnswerAsyncPreparationContractTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ElasticsearchClient elasticsearchClient;
    @Autowired ConversationEventMux conversationEventMux;

    private final List<String> workspaceIds = new ArrayList<>();

    @AfterEach
    void deleteWorkspaceIndices() {
        RetrievalIndexCleanup cleanup = new RetrievalIndexCleanup(elasticsearchClient);
        workspaceIds.forEach(cleanup::deleteWorkspaceIndices);
    }

    @Test
    void sendReturnsBeforePreparationAndPublishesStagesUntilGenerationStarts() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        List<ConversationLiveEvent> events = new CopyOnWriteArrayList<>();
        Thread follower = new Thread(() -> conversationEventMux.follow(
                conversationId, 0, events::add, Duration.ofSeconds(20), true));
        follower.setDaemon(true);
        follower.start();

        JsonNode receipt = submit(conversationId, Map.of(
                "content", "后台准备的第一个问题",
                "answer_mode", "QA",
                "client_request_id", "async-prepare-1"), 200);
        String runId = receipt.path("answer_run_id").asText();
        assertThat(runId).isNotBlank();
        assertThat(receipt.path("execution_kind").asText()).isEqualTo("ANSWER");

        awaitSubmissionLeavesPreparing(workspaceId, conversationId, "async-prepare-1");
        follower.join(20_000);

        List<String> stages = events.stream()
                .filter(event -> runId.equals(event.runId()))
                .filter(event -> AnswerTurnDispatcher.STAGE_EVENT.equals(event.eventType()))
                .map(event -> readStage(event.data()))
                .toList();
        assertThat(stages).containsSubsequence(
                AnswerPreparationListener.STAGE_UNDERSTANDING,
                AnswerPreparationListener.STAGE_RETRIEVING,
                AnswerPreparationListener.STAGE_RETRIEVED,
                AnswerPreparationListener.STAGE_WRITING);
        JsonNode retrieved = events.stream()
                .filter(event -> runId.equals(event.runId()))
                .filter(event -> AnswerTurnDispatcher.STAGE_EVENT.equals(event.eventType()))
                .map(event -> readTree(event.data()))
                .filter(node -> AnswerPreparationListener.STAGE_RETRIEVED.equals(node.path("stage").asText()))
                .findFirst().orElseThrow();
        assertThat(retrieved.has("evidence_count")).isTrue();
        // 阶段事件都在正文之前；准备完成后由后台启动生成，终态照常推送
        List<String> types = events.stream().filter(event -> runId.equals(event.runId()))
                .map(ConversationLiveEvent::eventType).toList();
        assertThat(types).containsSubsequence(
                AnswerTurnDispatcher.STAGE_EVENT, "evidence.ready", "answer.completed");
        assertThat(types.lastIndexOf(AnswerTurnDispatcher.STAGE_EVENT)).isLessThan(types.indexOf("evidence.ready"));
        assertThat(jdbcTemplate.queryForObject(
                "select status from turn_submission where conversation_id = ? and client_request_id = ?",
                String.class, conversationId, "async-prepare-1")).isEqualTo("READY");
    }

    @Test
    void admissionFailureIsStillReportedSynchronously() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);

        mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "invalid scoped note",
                                "answer_mode", "NOTE",
                                "client_request_id", "async-admission-1",
                                "source_scope_source_ids", List.of("00000000-0000-0000-0000-000000000001")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANSWER_SOURCE_SCOPE_UNSUPPORTED"));

        mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/turn-submissions/{clientRequestId}",
                        workspaceId, conversationId, "async-admission-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.error_code").value("ANSWER_SOURCE_SCOPE_UNSUPPORTED"));
    }

    void awaitSubmissionLeavesPreparing(String workspaceId, String conversationId, String clientRequestId)
            throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String runStatus = jdbcTemplate.queryForObject("""
                    select r.status from answer_run r join turn_submission t on t.answer_run_id = r.id
                    where t.workspace_id = ? and t.conversation_id = ? and t.client_request_id = ?
                    """, String.class, workspaceId, conversationId, clientRequestId);
            if ("FAILED".equals(runStatus) || "COMPLETED".equals(runStatus)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("answer run did not finish in time");
    }

    private String readStage(String data) {
        return readTree(data).path("stage").asText();
    }

    private JsonNode readTree(String data) {
        try {
            return objectMapper.readTree(data);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    JsonNode submit(String conversationId, Map<String, Object> request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    String createWorkspace() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "async-preparation",
                                "description", "background answer preparation"))))
                .andExpect(status().isOk())
                .andReturn();
        String workspaceId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("workspace_id").asText();
        workspaceIds.add(workspaceId);
        return workspaceId;
    }

    String createConversation(String workspaceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Async preparation",
                                "conversation_type", "WORKSPACE_CHAT"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("conversation_id").asText();
    }
}
