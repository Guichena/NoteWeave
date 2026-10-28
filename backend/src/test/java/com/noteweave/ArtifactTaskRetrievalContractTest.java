package com.noteweave;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.qa.ScopedPassageSearch;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestResearchOutboxPublisherConfig.class)
class ArtifactTaskRetrievalContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockBean ScopedPassageSearch search;

    @Test
    void retrievalUsesFrozenTaskScopeAndStopsWhenSourceIsRevoked() throws Exception {
        String workspace = data(mvc.perform(post("/api/v2/workspaces").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "检索测试", "description", "冻结范围验证"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("workspace_id").asText();
        String source = upload(workspace);
        String snapshot = jdbc.queryForObject("select id from source_snapshot where source_id = ? order by version_no desc limit 1", String.class, source);
        String chunk = jdbc.queryForObject("select id from source_chunk where source_snapshot_id = ? order by chunk_no limit 1", String.class, snapshot);
        JsonNode created = data(mvc.perform(post("/api/v2/workspaces/{workspace}/artifact-jobs", workspace)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "skill_key", "study_guide", "user_requirement", "解释恢复机制",
                        "inputs", Map.of("length", "STANDARD"), "source_scope_source_ids", List.of(source)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String task = created.path("task_id").asText();
        String newer = UUID.randomUUID().toString();
        jdbc.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status)
                select ?, source_id, file_object_id, version_no + 1, object_key, sha256, parse_status, index_status
                from source_snapshot where id = ?
                """, newer, snapshot);
        when(search.search(anyString(), any(), anyString(), anyInt())).thenReturn(new ScopedPassageSearch.Result(List.of(
                new ScopedPassageSearch.Passage(chunk, source, newer, 0, "新版本", "", "不能读取新版本正文", 1),
                new ScopedPassageSearch.Passage(chunk, source, snapshot, 0, "索引标题", "", "不能读取索引伪正文", 0.8)), false, List.of()));
        mvc.perform(post("/internal/worker/artifact-tasks/{task}/retrieve", task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"恢复机制\",\"limit\":6}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.task_id").value(task))
                .andExpect(jsonPath("$.data.passages[0].source_snapshot_id").value(snapshot))
                .andExpect(jsonPath("$.data.passages[0].content").value("冻结恢复机制通过日志重放完成。"))
                .andExpect(jsonPath("$.data.passages.length()").value(1)).andExpect(jsonPath("$.data.degraded").value(true));
        verify(search).search(workspace, Set.of(source), "恢复机制", 24);
        mvc.perform(post("/internal/worker/artifact-tasks/{task}/retrieve", task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"恢复机制\",\"limit\":21}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/worker/artifact-tasks/{task}/retrieve", task)
                .header("X-NoteWeave-Outbox-Delivery-Token", "stale")
                .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"恢复机制\",\"limit\":6}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORKER_INPUT_DELIVERY_STALE"));
        jdbc.update("update source set status = 'DELETED' where id = ?", source);
        mvc.perform(post("/internal/worker/artifact-tasks/{task}/retrieve", task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"恢复机制\",\"limit\":6}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ARTIFACT_SOURCE_REVOKED"));
        verifyNoMoreInteractions(search);
    }

    private JsonNode data(String response) throws Exception { return mapper.readTree(response).path("data"); }

    private String upload(String workspace) throws Exception {
        byte[] bytes = "冻结恢复机制通过日志重放完成。".getBytes(StandardCharsets.UTF_8);
        String upload = data(mvc.perform(post("/api/v2/workspaces/{workspace}/uploads", workspace)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "file_name", "恢复机制.md", "file_size", bytes.length, "mime_type", "text/markdown",
                        "chunk_size", bytes.length, "total_chunks", 1))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("upload_id").asText();
        mvc.perform(put("/api/v2/uploads/{upload}/chunks/0", upload)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(bytes)).andExpect(status().isOk());
        String source = data(mvc.perform(post("/api/v2/uploads/{upload}/complete", upload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("source_id").asText();
        jdbc.update("update source set status = 'READY', index_status = 'INDEXED' where id = ?", source);
        jdbc.update("update source_snapshot set index_status = 'INDEXED' where source_id = ?", source);
        return source;
    }
}
