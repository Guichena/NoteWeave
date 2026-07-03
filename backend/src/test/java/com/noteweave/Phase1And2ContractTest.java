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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.citation")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: chat.completed")));

        Integer citationCount = jdbcTemplate.queryForObject("select count(*) from citation", Integer.class);
        Integer messageCitationCount = jdbcTemplate.queryForObject("select count(*) from message_citation", Integer.class);
        assertThat(citationCount).isNotNull().isGreaterThan(0);
        assertThat(messageCitationCount).isNotNull().isGreaterThan(0);
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
}
