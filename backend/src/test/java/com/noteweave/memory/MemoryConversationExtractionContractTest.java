package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemoryConversationExtractionContractTest {

    private static final String TOPIC = "noteweave.memory.extraction";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryConversationExtractionService extractionService;

    @Test
    void explicitLongTermPreferenceInChatBecomesActiveMemoryThroughTheGate() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        String preferenceMessage = send(conversationId, "记住：以后回答先给结论再展开。", "extract-1");
        String ordinaryMessage = send(conversationId, "解释一下缓存一致性", "extract-2");

        // 只有带长期要求措辞的消息才进入提取任务
        assertThat(jdbc.queryForObject("select count(*) from task_outbox where topic = ? and message_key = ?",
                Integer.class, TOPIC, ordinaryMessage)).isZero();
        JsonNode payload = mapper.readTree(jdbc.queryForObject(
                "select payload_json from task_outbox where topic = ? and message_key = ?",
                String.class, TOPIC, preferenceMessage));
        assertThat(payload.path("user_id").asText()).isNotBlank();

        MemoryConversationExtractionService.ExtractionResult result = consume(payload);
        assertThat(result.method()).isEqualTo("RULE");
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.statement()).isEqualTo("以后回答先给结论再展开");
            assertThat(candidate.sourceType()).isEqualTo("CONVERSATION_FEEDBACK");
            assertThat(candidate.reviewStatus()).isEqualTo("READY");
        });

        JsonNode items = data(get("/api/v2/workspaces/{workspaceId}/memory/items", workspaceId));
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.path("display_text").asText()).isEqualTo("以后回答先给结论再展开");
            assertThat(item.path("revision_status").asText()).isEqualTo("ACTIVE");
            assertThat(item.path("gate").path("source_type").asText()).isEqualTo("CONVERSATION_FEEDBACK");
            assertThat(item.path("gate").path("source_ref").asText())
                    .isEqualTo("conversation-message:" + preferenceMessage + ":0");
        });
        // 生效后进入表达控制，结构类偏好编入结构约束
        JsonNode pack = data(get("/api/v2/workspaces/{workspaceId}/memory/control-pack/chat", workspaceId)
                .param("answer_mode", "QA"));
        assertThat(pack.path("structure_constraints").toString()).contains("以后回答先给结论再展开");
        assertThat(jdbc.queryForObject("select task_status from task where id = ?", String.class,
                payload.path("task_id").asText())).isEqualTo("COMPLETED");

        // 重复投递不会产生重复的记忆
        assertThat(consume(payload).method()).isEqualTo("DUPLICATE_DELIVERY");
        assertThat(data(get("/api/v2/workspaces/{workspaceId}/memory/items", workspaceId))).hasSize(1);
    }

    @Test
    void inferredPreferenceWaitsForConfirmationAndDuplicatesAreSkipped() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        String first = send(conversationId, "以后不要使用表情符号", "infer-1");
        consume(payloadOf(first));

        // 模型推断出的偏好可信度低于门槛，进入待确认；与已有记忆相同的偏好被跳过
        String userId = jdbc.queryForObject("select user_id from memory_signal where source_id like ?",
                String.class, "conversation-message:" + first + ":%");
        List<MemoryConversationExtractionService.Preference> inferred = new ArrayList<>(List.of(
                new MemoryConversationExtractionService.Preference("以后不要使用表情符号", "forbidden", true),
                new MemoryConversationExtractionService.Preference("回答时多用类比解释概念", "style", false)));
        MemoryConversationExtractionService.ExtractionResult result = extractionService.persistExtraction(
                null, workspaceId, "manual-message", userId,
                new MemoryConversationExtractionService.Extraction(inferred, "LLM"));

        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.statement()).isEqualTo("回答时多用类比解释概念");
            assertThat(candidate.sourceType()).isEqualTo("MODEL_INFERENCE");
            assertThat(candidate.reviewStatus()).isEqualTo("NEEDS_REVIEW");
        });
        JsonNode items = data(get("/api/v2/workspaces/{workspaceId}/memory/items", workspaceId));
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("display_text").asText()).isEqualTo("回答时多用类比解释概念");
        assertThat(items.get(0).path("revision_status").asText()).isEqualTo("PROPOSED");
        assertThat(items.get(0).path("gate").path("result").asText()).isEqualTo("NEEDS_REVIEW");
    }

    private MemoryConversationExtractionService.ExtractionResult consume(JsonNode payload) {
        return extractionService.extract(payload.path("task_id").asText(), payload.path("workspace_id").asText(),
                payload.path("message_id").asText(), payload.path("user_id").asText(),
                payload.path("content").asText());
    }

    private JsonNode payloadOf(String messageId) throws Exception {
        return mapper.readTree(jdbc.queryForObject(
                "select payload_json from task_outbox where topic = ? and message_key = ?",
                String.class, TOPIC, messageId));
    }

    private String send(String conversationId, String content, String clientRequestId) throws Exception {
        return data(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("content", content, "answer_mode", "QA",
                        "client_request_id", clientRequestId)))).path("message_id").asText();
    }

    private String createWorkspace() throws Exception {
        return data(post("/api/v2/workspaces").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "memory-extraction",
                        "description", "conversation memory extraction")))).path("workspace_id").asText();
    }

    private String createConversation(String workspaceId) throws Exception {
        return data(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("title", "memory extraction",
                        "conversation_type", "WORKSPACE_CHAT")))).path("conversation_id").asText();
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return mapper.readTree(body).path("data");
    }
}
