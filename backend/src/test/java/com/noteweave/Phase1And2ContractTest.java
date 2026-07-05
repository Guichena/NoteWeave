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
import com.noteweave.infra.LocalObjectStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
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
class Phase1And2ContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LocalObjectStorage storage;

    @Test
    void phase1And2ShouldRunWorkspaceUploadRagCitationFlow() throws Exception {
        String workspaceId = createWorkspace();
        String uploadId = createUpload(workspaceId);

        byte[] first = "NoteWeave 是一个 NotebookLM 形态的研究工作台。\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = "阶段2实现资料上传、解析切片、问答RAG和引用闭环。".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, 0)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true));

        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, 1)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true));

        MvcResult completeResult = mockMvc.perform(post("/api/v2/uploads/{uploadId}/complete", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parse_status").value("PARSED"))
                .andExpect(jsonPath("$.data.index_status").value("INDEXED"))
                .andReturn();

        JsonNode complete = objectMapper.readTree(completeResult.getResponse().getContentAsString());
        String sourceId = complete.path("data").path("source_id").asText();
        String taskId = complete.path("data").path("task_id").asText();
        assertThat(sourceId).isNotBlank();

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.result_ref").value(sourceId));

        mockMvc.perform(get("/api/v2/tasks/{taskId}/events", taskId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: task.status")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: task.completed")));

        String conversationId = createConversation(workspaceId);
        MvcResult messageResult = mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "阶段2实现了什么？",
                                "answer_mode", "QA",
                                "client_request_id", "req-1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistant_request_id").isNotEmpty())
                .andReturn();

        JsonNode message = objectMapper.readTree(messageResult.getResponse().getContentAsString());
        String assistantRequestId = message.path("data").path("assistant_request_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", assistantRequestId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 直接回答")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 证据选择")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("查询意图")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Deep Research"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.completed")));

        Integer citationCount = jdbcTemplate.queryForObject("select count(*) from citation", Integer.class);
        Integer messageCitationCount = jdbcTemplate.queryForObject("select count(*) from message_citation", Integer.class);
        assertThat(citationCount).isNotNull().isGreaterThan(0);
        assertThat(messageCitationCount).isNotNull().isGreaterThan(0);
    }

    @Test
    void qaRagShouldSelectDiverseEvidenceAcrossWorkspaceSources() throws Exception {
        String workspaceId = createWorkspace();
        completeSingleChunkUpload(workspaceId, "rag-a.md", """
                RAG 资料 A 说明检索增强生成需要先做工作台级资料召回，再把证据片段打包给模型。
                它强调 citation-grounded answer，不能把聊天历史当事实来源。
                """.getBytes(StandardCharsets.UTF_8));
        completeSingleChunkUpload(workspaceId, "rag-b.md", """
                RAG 资料 B 说明证据选择要覆盖不同来源，比较类问题应该避免只拿同一份资料的多个片段。
                它强调 evidence rerank、source diversity 和 citation 回跳。
                """.getBytes(StandardCharsets.UTF_8));
        String conversationId = createConversation(workspaceId);

        MvcResult messageResult = mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "比较 RAG 的证据选择和 citation 要求",
                                "answer_mode", "QA",
                                "client_request_id", "qa-diverse"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode message = objectMapper.readTree(messageResult.getResponse().getContentAsString());
        String assistantRequestId = message.path("data").path("assistant_request_id").asText();
        String assistantMessageId = message.path("data").path("assistant_message_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", assistantRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("查询意图：comparison")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("来源覆盖")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("RAG 资料 A")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("RAG 资料 B")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        Integer distinctSourceCount = jdbcTemplate.queryForObject("""
                select count(distinct c.source_id)
                from message_citation mc
                join citation c on c.id = mc.citation_id
                where mc.message_id = ?
                """, Integer.class, assistantMessageId);
        assertThat(distinctSourceCount).isNotNull().isGreaterThanOrEqualTo(2);
    }

    @Test
    void qaModeShouldCarryRollingConversationContextForFollowUpQuestions() throws Exception {
        String workspaceId = createWorkspace();
        completeSingleChunkUpload(workspaceId, "alpha-context.md", """
                AlphaSpec 的关键要求包括分阶段发布、证据留痕和来源可回跳。
                AlphaSpec 还要求回答过程优先围绕当前主题持续展开，而不是切到别的资料。
                AlphaSpec 的第二个关键要求是证据留痕，这能保证后续追问仍然围绕同一主题继续验证。
                """.getBytes(StandardCharsets.UTF_8));
        completeSingleChunkUpload(workspaceId, "beta-context.md", """
                BetaSpec 讨论的是离线导入、批量解析和异步索引。
                它和 AlphaSpec 的关键要求不是同一个主题。
                """.getBytes(StandardCharsets.UTF_8));
        String conversationId = createConversation(workspaceId);

        sendMessage(conversationId, "QA", "先介绍 AlphaSpec 的关键要求");
        sendMessage(conversationId, "QA", "把它分成两点讲");
        JsonNode followUp = sendMessage(conversationId, "QA", "第二点为什么重要");
        String assistantRequestId = followUp.path("data").path("assistant_request_id").asText();
        String assistantMessageId = followUp.path("data").path("assistant_message_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", assistantRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("会话上下文：已纳入最近连续对话窗口")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("连续对话窗口：最近 2 轮相关对话")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("主题锚点：")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AlphaSpec")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        Integer alphaCitationCount = jdbcTemplate.queryForObject("""
                select count(*)
                from message_citation mc
                join citation c on c.id = mc.citation_id
                where mc.message_id = ? and c.title = 'alpha-context.md'
                """, Integer.class, assistantMessageId);
        assertThat(alphaCitationCount).isNotNull().isGreaterThan(0);
    }

    @Test
    void qaModeShouldCompileOlderSameTopicTurnsIntoTopicSummary() throws Exception {
        String workspaceId = createWorkspace();
        completeSingleChunkUpload(workspaceId, "alpha-summary.md", """
                AlphaSpec 包含四类连续相关要求：分阶段发布、证据留痕、来源回跳和主题连续推进。
                这些要求共同强调回答链路要围绕同一主题逐步展开，并保留可验证依据。
                """.getBytes(StandardCharsets.UTF_8));
        String conversationId = createConversation(workspaceId);

        sendMessage(conversationId, "QA", "AlphaSpec 的分阶段发布要求是什么");
        sendMessage(conversationId, "QA", "AlphaSpec 的证据留痕要求是什么");
        sendMessage(conversationId, "QA", "AlphaSpec 的来源回跳要求是什么");
        sendMessage(conversationId, "QA", "AlphaSpec 的主题连续要求是什么");
        JsonNode followUp = sendMessage(conversationId, "QA", "继续总结它们的共同原则");
        String assistantRequestId = followUp.path("data").path("assistant_request_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", assistantRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("会话上下文：已纳入最近连续对话窗口")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("连续对话窗口：最近 3 轮相关对话")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("前序主题摘要：")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AlphaSpec")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));
    }

    @Test
    void qaModeShouldCutOffOldWindowWhenUserStartsANewExplicitTopic() throws Exception {
        String workspaceId = createWorkspace();
        completeSingleChunkUpload(workspaceId, "alpha-topic.md", """
                AlphaSpec 关注主题连续展开和证据留痕。
                """.getBytes(StandardCharsets.UTF_8));
        completeSingleChunkUpload(workspaceId, "beta-topic.md", """
                BetaSpec 的重点是离线导入、批量解析和异步索引。
                """.getBytes(StandardCharsets.UTF_8));
        String conversationId = createConversation(workspaceId);

        sendMessage(conversationId, "QA", "先介绍 AlphaSpec 的关键要求");
        JsonNode followUp = sendMessage(conversationId, "QA", "请介绍 BetaSpec 的离线导入要求");
        String assistantRequestId = followUp.path("data").path("assistant_request_id").asText();
        String assistantMessageId = followUp.path("data").path("assistant_message_id").asText();

        mockMvc.perform(get("/api/v2/chat/requests/{assistantRequestId}/stream", assistantRequestId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("会话上下文：已纳入最近连续对话窗口"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BetaSpec")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")));

        Integer betaCitationCount = jdbcTemplate.queryForObject("""
                select count(*)
                from message_citation mc
                join citation c on c.id = mc.citation_id
                where mc.message_id = ? and c.title = 'beta-topic.md'
                """, Integer.class, assistantMessageId);
        assertThat(betaCitationCount).isNotNull().isGreaterThan(0);
    }

    @Test
    void createWorkspaceShouldValidateName() throws Exception {
        mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void duplicateUploadsShouldReuseCanonicalFileObject() throws Exception {
        String workspaceId = createWorkspace();
        byte[] content = "同一份资料重复上传时应该复用 file_object，并保留可读取的规范化对象。".getBytes(StandardCharsets.UTF_8);

        completeSingleChunkUpload(workspaceId, "duplicate-a.md", content);
        completeSingleChunkUpload(workspaceId, "duplicate-b.md", content);

        Map<String, Object> fileObject = jdbcTemplate.queryForMap("""
                select id, object_key, ref_count from file_object where workspace_id = ?
                """, workspaceId);
        Integer fileObjectCount = jdbcTemplate.queryForObject(
                "select count(*) from file_object where workspace_id = ?",
                Integer.class,
                workspaceId
        );
        Integer sourceCount = jdbcTemplate.queryForObject(
                "select count(*) from source where workspace_id = ?",
                Integer.class,
                workspaceId
        );

        assertThat(fileObjectCount).isEqualTo(1);
        assertThat(sourceCount).isEqualTo(2);
        assertThat(fileObject.get("ref_count")).isEqualTo(2);
        assertThat(storage.exists((String) fileObject.get("object_key"))).isTrue();
        assertThat(storage.read((String) fileObject.get("object_key"))).isEqualTo(content);
    }

    @Test
    void uploadChunkShouldValidateOptionalContentMd5() throws Exception {
        String workspaceId = createWorkspace();
        String uploadId = createUpload(workspaceId);
        byte[] content = "需要被校验的资料分片".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, 0)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("Content-MD5", "bad-md5")
                        .content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UPLOAD_CHUNK_MD5_MISMATCH"));

        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, 0)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("Content-MD5", base64Md5(content))
                        .content(content))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true));
    }

    private String createWorkspace() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "阶段2工作台",
                                "description", "用于阶段1/2契约测试"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspace_id").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("workspace_id").asText();
    }

    private String createUpload(String workspaceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/uploads", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "file_name", "phase2.md",
                                "file_size", 128,
                                "mime_type", "text/markdown",
                                "chunk_size", 64,
                                "total_chunks", 2
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.upload_id").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("upload_id").asText();
    }

    private void completeSingleChunkUpload(String workspaceId, String fileName, byte[] content) throws Exception {
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
                .andExpect(jsonPath("$.data.parse_status").value("PARSED"))
                .andExpect(jsonPath("$.data.index_status").value("INDEXED"));
    }

    private String createConversation(String workspaceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "阶段2问答",
                                "conversation_type", "WORKSPACE_CHAT"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversation_id").isNotEmpty())
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

    private String base64Md5(byte[] content) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(content));
    }
}
