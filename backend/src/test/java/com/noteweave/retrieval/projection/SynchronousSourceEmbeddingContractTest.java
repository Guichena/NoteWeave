package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.knowledge.WikiIndexOutbox;
import com.noteweave.retrieval.index.RetrievalIndexManager;
import com.noteweave.retrieval.index.RetrievalProjectionWriter;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.upload.CreateUploadRequest;
import com.noteweave.upload.UploadService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.MockReset;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 真正提交上传事务后，检查同步投影与上下文结果已经持久化，而非只留在连接里。 */
// 独立内存库：测试进程内所有上下文共享 `jdbc:h2:mem:noteweave`（DB_CLOSE_DELAY=-1），
// 别的测试类遗留的 task_outbox/wiki_index_outbox/source 行会被本上下文的 @Scheduled 发件箱
// 扫到并调用本类的 EmbeddingClient mock，导致同步投影失败落成 source.status='FAILED'。
@SpringBootTest(properties = {"noteweave.elasticsearch.enabled=true", "noteweave.embedding.dimensions=3",
        "noteweave.embedding.model=sync-test-model", "noteweave.kafka.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:noteweave_sync_embedding_contract;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SynchronousSourceEmbeddingContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UploadService uploads;
    // reset = NONE：全量运行时别的上下文/测试类触发的 Mockito 重置不能把下面的打桩清掉，
    // 否则未打桩的 mock 会返回 null EmbeddingResult，同步投影直接 NPE 并落成 FAILED。
    @MockBean(reset = MockReset.NONE) EmbeddingClient embedding;
    @MockBean RetrievalIndexManager indexes;
    @MockBean RetrievalProjectionWriter writer;
    /** Wiki 默认开启后，共享库里的索引发件箱会在本测试打桩期间调用同一个 EmbeddingClient mock。 */
    @MockBean WikiIndexOutbox wikiIndexOutbox;

    @BeforeEach
    void stubEmbeddingAndIndex() {
        when(embedding.isEnabled()).thenReturn(true);
        when(embedding.embedDocuments(anyList())).thenAnswer(call -> {
            List<String> texts = call.getArgument(0);
            return new EmbeddingClient.EmbeddingResult(texts.stream().map(ignored -> List.of(1F, 0F, 0F)).toList(),
                    "sync-test-model", 3, 0);
        });
        // 后台发件箱（Wiki/Memory）也会走这个 mock，未打桩时返回 null 会在同一次运行里打出 NPE。
        when(embedding.embedQuery(anyString())).thenReturn(
                new EmbeddingClient.EmbeddingResult(List.of(List.of(1F, 0F, 0F)), "sync-test-model", 3, 0));
        when(indexes.resolveWriteIndex(anyString(), anyString())).thenAnswer(call -> call.getArgument(1, String.class));
    }

    @Test
    void committedUploadPersistsContextProjectionAndReadyStatus() throws Exception {
        String workspace = mapper.readTree(mvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("name", "同步向量回归"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("workspace_id").asText();
        jdbc.update("update workspace set wiki_enabled = false where id = ?", workspace);
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
