package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.index.RetrievalIndexManager;
import com.noteweave.retrieval.index.RetrievalProjectionWriter;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.upload.CreateUploadRequest;
import com.noteweave.upload.UploadService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 真正提交上传事务后，检查同步投影与上下文结果已经持久化，而非只留在连接里。 */
@SpringBootTest(properties = {"noteweave.elasticsearch.enabled=true", "noteweave.embedding.dimensions=3",
        "noteweave.embedding.model=sync-test-model", "noteweave.kafka.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SynchronousSourceEmbeddingContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UploadService uploads;
    @MockBean EmbeddingClient embedding;
    @MockBean RetrievalIndexManager indexes;
    @MockBean RetrievalProjectionWriter writer;

    @Test
    void committedUploadPersistsContextProjectionAndReadyStatus() throws Exception {
        when(embedding.isEnabled()).thenReturn(true);
        when(embedding.embedDocuments(anyList())).thenAnswer(call -> {
            List<String> texts = call.getArgument(0);
            return new EmbeddingClient.EmbeddingResult(texts.stream().map(ignored -> List.of(1F, 0F, 0F)).toList(),
                    "sync-test-model", 3, 0);
        });
        when(indexes.resolveWriteIndex(anyString(), anyString())).thenAnswer(call -> call.getArgument(1, String.class));
        String workspace = mapper.readTree(mvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("name", "同步向量回归"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("workspace_id").asText();
        byte[] body = "# 资料标题\n\n原文与上下文分别保存，资料提交后可以读取。".getBytes(StandardCharsets.UTF_8);
        String upload = uploads.createUpload(workspace, new CreateUploadRequest("同步.md", body.length,
                "text/markdown", body.length, 1)).uploadId();
        uploads.acceptChunk(upload, 0, null, body);
        String source = uploads.completeUpload(upload).sourceId();
        assertThat(jdbc.queryForObject("select status from source where id = ?", String.class, source)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("select count(*) from source_chunk where source_id = ? and context_prompt_version is not null",
                Integer.class, source)).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from retrieval_projection where source_id = ? and status = 'READY'",
                Integer.class, source)).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from source_embedding_cache where workspace_id = ?",
                Integer.class, workspace)).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from task_outbox where topic = 'noteweave.retrieval.projection' and status = 'SENT'",
                Integer.class)).isPositive();
    }
}
