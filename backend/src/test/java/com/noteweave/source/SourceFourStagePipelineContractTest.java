package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.retrieval.index.RetrievalIndexManager;
import com.noteweave.retrieval.index.RetrievalProjectionWriter;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository;
import com.noteweave.retrieval.projection.SnapshotEmbeddingStore;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionCoordinator;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionFinalizer;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionService;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 资料处理四个阶段的契约：每个阶段只在快照处于对应阶段时执行并投递下一阶段，
 * 重复消息不会重复处理；索引阶段复用向量化阶段写入的向量；索引失败后按退避自动重试，也可以手动重新处理。
 * 测试环境没有 Kafka 和 Elasticsearch，这里用异步模式构造各阶段服务并逐个调用，向量服务和索引写入用替身。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SourceFourStagePipelineContractTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentChunker chunker;
    @Autowired SourceDocumentTextExtractor extractor;
    @Autowired ObjectStorage storage;
    @Autowired NoteWeaveProperties properties;
    @Autowired TaskService taskService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AuditActorProvider auditActorProvider;
    @Autowired SourceCatalogVersionService catalogVersionService;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired RetrievalProjectionRepository projectionRepository;
    @Autowired SourceRetrievalProjectionFinalizer finalizer;
    @Autowired SourceTagCodec tagCodec;
    @Autowired WorkspaceAccessGuard accessGuard;
    @Autowired SourceParsePort sourceParsePort;
    @Autowired SourceTranscriptionService transcriptionService;

    @Test
    void eachStageRunsOnceAndHandsItsResultToTheNextStage() throws Exception {
        Fixture fixture = pendingSource("four-stage.md", "# 缓存一致性\n\n" + "先更新数据库，再删除缓存。\n\n".repeat(80));
        Pipeline pipeline = pipeline(fakeEmbeddings());

        // 解析：提取文本写入派生存储，只投递切片阶段，不产生片段
        assertThat(pipeline.parse().parseAndIndexAsyncIfProcessable(fixture.workspaceId(), fixture.sourceId(),
                fixture.snapshotId())).isTrue();
        assertThat(stage(fixture)).isEqualTo("CHUNKING");
        assertThat(count("select count(*) from source_chunk where source_snapshot_id = ?", fixture.snapshotId())).isZero();
        JsonNode chunkPayload = lastPayload(SourcePipelineStages.TOPIC_CHUNK, fixture.snapshotId());
        assertThat(new String(storage.read(SourcePipelineStages.BUCKET_DERIVED,
                chunkPayload.path("textObjectKey").asText()), StandardCharsets.UTF_8)).contains("缓存一致性");
        // 重复的解析消息：快照已进入后续阶段，只确认，不取消任务
        assertThat(pipeline.parse().assessProcessability(fixture.workspaceId(), fixture.sourceId(),
                fixture.snapshotId()).disposition()).isEqualTo(SourceParseService.SourceParseDisposition.IN_LATER_STAGE);

        // 切片：生成片段后投递向量化阶段；重复执行不会重复切片
        assertThat(chunk(pipeline, fixture, chunkPayload)).isTrue();
        int chunks = count("select count(*) from source_chunk where source_snapshot_id = ?", fixture.snapshotId());
        assertThat(chunks).isGreaterThan(1);
        assertThat(stage(fixture)).isEqualTo("EMBEDDING");
        assertThat(chunk(pipeline, fixture, chunkPayload)).isFalse();
        assertThat(count("select count(*) from source_chunk where source_snapshot_id = ?", fixture.snapshotId()))
                .isEqualTo(chunks);
        lastPayload(SourcePipelineStages.TOPIC_EMBED, fixture.snapshotId());

        // 向量化：计算片段和资料的向量写入派生存储，投递索引阶段
        assertThat(pipeline.coordinator().embedStage(fixture.workspaceId(), fixture.sourceId(),
                fixture.snapshotId(), fixture.taskId())).isTrue();
        assertThat(stage(fixture)).isEqualTo("INDEXING");
        verify(pipeline.embeddings(), times(2)).embedDocuments(anyList());
        assertThat(pipeline.coordinator().embedStage(fixture.workspaceId(), fixture.sourceId(),
                fixture.snapshotId(), fixture.taskId())).isFalse();
        JsonNode indexPayload = lastPayload(SourcePipelineStages.TOPIC_INDEX, fixture.snapshotId());

        // 索引：直接使用已保存的向量，不再调用向量服务
        assertThat(pipeline.coordinator().indexStage(fixture.workspaceId(), fixture.sourceId(),
                fixture.snapshotId(), fixture.taskId(), indexPayload.path("embeddingsObjectKey").asText())).isTrue();
        verify(pipeline.embeddings(), times(2)).embedDocuments(anyList());
        verify(pipeline.writer(), times(chunks)).writeQaChunk(anyString(), any());
        assertThat(stage(fixture)).isEqualTo("READY");
        assertThat(jdbc.queryForMap("select status, index_status from source where id = ?", fixture.sourceId()))
                .containsEntry("status", "READY").containsEntry("index_status", "INDEXED");
        assertThat(jdbc.queryForObject("select task_status from task where id = ?", String.class, fixture.taskId()))
                .isEqualTo("COMPLETED");
        // 任务事件里依次记录了各阶段
        assertThat(jdbc.queryForList("""
                select message from task_event where task_id = ? and event_type = 'TASK_PROGRESS' order by created_at, id
                """, String.class, fixture.taskId()))
                .anySatisfy(message -> assertThat(message).startsWith("解析完成"))
                .anySatisfy(message -> assertThat(message).startsWith("切片完成"))
                .anySatisfy(message -> assertThat(message).startsWith("向量化完成"));
    }

    @Test
    void failedIndexRetriesAutomaticallyWithBackoffAndCanBeReprocessedByHand() throws Exception {
        Fixture fixture = pendingSource("retry.md", "# 延迟双删\n\n" + "删除缓存后等待一段时间再删一次。\n\n".repeat(40));
        EmbeddingClient failing = mock(EmbeddingClient.class);
        when(failing.isEnabled()).thenReturn(true);
        when(failing.embedDocuments(anyList())).thenThrow(
                new RetrievalProviderException("EMBEDDING_PROVIDER_TIMEOUT", "timeout"));
        Pipeline pipeline = pipeline(failing);
        pipeline.parse().parseAndIndexAsyncIfProcessable(fixture.workspaceId(), fixture.sourceId(), fixture.snapshotId());
        chunk(pipeline, fixture, lastPayload(SourcePipelineStages.TOPIC_CHUNK, fixture.snapshotId()));

        assertThatThrownBy(() -> pipeline.coordinator().embedStage(fixture.workspaceId(), fixture.sourceId(),
                fixture.snapshotId(), fixture.taskId())).isInstanceOf(RetrievalProviderException.class);
        // 消费者重试耗尽进入死信后的收尾：可重试的错误写入下次自动重试时间，任务标记为可重试
        finalizer.finalizeFailed(fixture.workspaceId(), fixture.sourceId(), fixture.snapshotId(), fixture.taskId(),
                "EMBEDDING_PROVIDER_TIMEOUT");
        Map<String, Object> failed = jdbc.queryForMap("""
                select index_status, processing_stage, index_attempt_count, next_index_retry_at
                from source_snapshot where id = ?
                """, fixture.snapshotId());
        assertThat(failed).containsEntry("index_status", "FAILED").containsEntry("processing_stage", "EMBEDDING");
        assertThat(failed.get("next_index_retry_at")).isNotNull();

        // 到期后调度器从向量化阶段重新投递，计入一次自动重试
        jdbc.update("update source_snapshot set next_index_retry_at = timestamp '2020-01-01 00:00:00' where id = ?",
                fixture.snapshotId());
        new SourceIndexRetryScheduler(jdbc, pipeline.reprocess(), transactionManager).retryDueSnapshots();
        assertThat(jdbc.queryForMap("""
                select index_status, processing_stage, index_attempt_count, next_index_retry_at
                from source_snapshot where id = ?
                """, fixture.snapshotId()))
                .containsEntry("index_status", "INDEXING").containsEntry("processing_stage", "EMBEDDING")
                .containsEntry("index_attempt_count", 1).containsEntry("next_index_retry_at", null);
        JsonNode retryPayload = lastPayload(SourcePipelineStages.TOPIC_EMBED, fixture.snapshotId());
        String retryTaskId = retryPayload.path("taskId").asText();
        assertThat(retryTaskId).isNotEqualTo(fixture.taskId());
        assertThat(jdbc.queryForObject("select progress_message from task where id = ?", String.class, retryTaskId))
                .contains("自动重试");

        // 配置类错误重试也不会成功，不再安排自动重试；此时可以手动重新处理
        finalizer.finalizeFailed(fixture.workspaceId(), fixture.sourceId(), fixture.snapshotId(), retryTaskId,
                "EMBEDDING_PROVIDER_DISABLED");
        assertThat(jdbc.queryForObject("select next_index_retry_at from source_snapshot where id = ?",
                java.sql.Timestamp.class, fixture.snapshotId())).isNull();
        SourceReprocessResponse manual = pipeline.reprocess().reprocess(fixture.workspaceId(), fixture.sourceId());
        assertThat(manual.restartFrom()).isEqualTo("INDEX");
        assertThat(jdbc.queryForObject("select index_attempt_count from source_snapshot where id = ?",
                Integer.class, fixture.snapshotId())).isZero();
        assertThat(lastPayload(SourcePipelineStages.TOPIC_EMBED, fixture.snapshotId()).path("taskId").asText())
                .isEqualTo(manual.taskId());
    }

    @Test
    void reprocessEndpointRestartsAFailedParseAndRejectsHealthySources() throws Exception {
        String workspaceId = createWorkspace();
        String sourceId = upload(workspaceId, "broken.md", "# 重新处理\n\n解析失败后重新处理。");
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/sources/{sourceId}/reprocess", workspaceId, sourceId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_REPROCESS_NOT_NEEDED"));

        String snapshotId = jdbc.queryForObject("select id from source_snapshot where source_id = ?", String.class, sourceId);
        jdbc.update("""
                delete from source_window where source_chunk_id in (select id from source_chunk where source_snapshot_id = ?)
                """, snapshotId);
        jdbc.update("delete from source_chunk where source_snapshot_id = ?", snapshotId);
        jdbc.update("update source_snapshot set parse_status = 'FAILED', index_status = 'FAILED' where id = ?", snapshotId);
        jdbc.update("update source set status = 'FAILED', parse_status = 'FAILED', index_status = 'FAILED' where id = ?",
                sourceId);

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/sources/{sourceId}/reprocess", workspaceId, sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restart_from").value("PARSE"))
                .andExpect(jsonPath("$.data.task_id").isNotEmpty());
        // 测试环境同步解析：重新解析后片段恢复，资料可用
        assertThat(jdbc.queryForObject("select parse_status from source where id = ?", String.class, sourceId))
                .isEqualTo("PARSED");
        assertThat(count("select count(*) from source_chunk where source_snapshot_id = ?", snapshotId)).isPositive();
    }

    @Test
    void audioSourceIsTranscribedByTheWorkerAndThenChunkedLikeText() throws Exception {
        Fixture fixture = pendingMediaSource("组会录音.mp3", "audio/mpeg", "ID3\u0004audio-bytes".getBytes(StandardCharsets.ISO_8859_1));
        Pipeline pipeline = pipeline(fakeEmbeddings());
        List<SourceTranscriptionPort.Request> submitted = new ArrayList<>();
        List<byte[]> uploaded = new ArrayList<>();
        pipeline.parse().setTranscriptionPort((request, file) -> {
            submitted.add(request);
            try {
                uploaded.add(java.nio.file.Files.readAllBytes(file));
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        });

        // 解析阶段：音视频不做文本提取，原件交给 Worker 转写，快照进入转写中
        assertThat(pipeline.parse().parseAndIndexAsyncIfProcessable(fixture.workspaceId(), fixture.sourceId(),
                fixture.snapshotId())).isTrue();
        assertThat(stage(fixture)).isEqualTo("TRANSCRIBING");
        assertThat(submitted).singleElement().satisfies(request -> {
            assertThat(request.snapshotId()).isEqualTo(fixture.snapshotId());
            assertThat(request.mimeType()).isEqualTo("audio/mpeg");
        });
        assertThat(new String(uploaded.get(0), StandardCharsets.ISO_8859_1)).startsWith("ID3");
        assertThat(pipeline.parse().assessProcessability(fixture.workspaceId(), fixture.sourceId(),
                fixture.snapshotId()).disposition()).isEqualTo(SourceParseService.SourceParseDisposition.IN_LATER_STAGE);

        // Worker 回调文字稿：写入派生存储并投递切片阶段；重复回调不会重复投递
        String transcript = "[00:01] 今天讨论缓存一致性\n[01:05] 结论是先更新数据库再删除缓存";
        callback(fixture, Map.of("workspace_id", fixture.workspaceId(), "source_id", fixture.sourceId(),
                "status", "COMPLETED", "transcript_text", transcript, "duration_seconds", 130.0, "segment_count", 2))
                .andExpect(jsonPath("$.data.accepted").value(true));
        assertThat(stage(fixture)).isEqualTo("CHUNKING");
        callback(fixture, Map.of("workspace_id", fixture.workspaceId(), "source_id", fixture.sourceId(),
                "status", "COMPLETED", "transcript_text", transcript))
                .andExpect(jsonPath("$.data.accepted").value(false));
        JsonNode chunkPayload = lastPayload(SourcePipelineStages.TOPIC_CHUNK, fixture.snapshotId());
        assertThat(count("select count(*) from task_outbox where topic = ? and message_key = ?",
                SourcePipelineStages.TOPIC_CHUNK, fixture.snapshotId())).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                select message from task_event where task_id = ? and event_type = 'TASK_PROGRESS' order by created_at, id
                """, String.class, fixture.taskId()))
                .anySatisfy(message -> assertThat(message).startsWith("已提交音视频转写"))
                .anySatisfy(message -> assertThat(message).startsWith("转写完成（时长约 2 分钟）"));

        // 之后和文本资料一样切片，片段保留时间戳
        assertThat(chunk(pipeline, fixture, chunkPayload)).isTrue();
        assertThat(jdbc.queryForList("select content from source_chunk where source_snapshot_id = ?",
                String.class, fixture.snapshotId())).anySatisfy(content -> assertThat(content).contains("[01:05]"));
        assertThat(jdbc.queryForObject("select metadata_json from source where id = ?", String.class, fixture.sourceId()))
                .contains("audio/mpeg");
        assertThat(stage(fixture)).isEqualTo("EMBEDDING");
    }

    @Test
    void failedOrStaleTranscriptionEndsAsAParseFailure() throws Exception {
        Fixture failed = pendingMediaSource("坏文件.m4a", "audio/mp4", "....ftypM4A ".getBytes(StandardCharsets.ISO_8859_1));
        Fixture stale = pendingMediaSource("超时.wav", "audio/wav",
                "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1));
        Pipeline pipeline = pipeline(fakeEmbeddings());
        pipeline.parse().setTranscriptionPort((request, file) -> { });
        pipeline.parse().parseAndIndexAsyncIfProcessable(failed.workspaceId(), failed.sourceId(), failed.snapshotId());
        pipeline.parse().parseAndIndexAsyncIfProcessable(stale.workspaceId(), stale.sourceId(), stale.snapshotId());

        callback(failed, Map.of("workspace_id", failed.workspaceId(), "source_id", failed.sourceId(),
                "status", "FAILED", "error_code", "SOURCE_TRANSCRIPTION_FAILED", "error_message", "音视频转写失败：模型下载失败"))
                .andExpect(jsonPath("$.data.accepted").value(true));
        assertThat(jdbc.queryForMap("select status, parse_status from source where id = ?", failed.sourceId()))
                .containsEntry("status", "FAILED").containsEntry("parse_status", "FAILED");
        assertThat(jdbc.queryForObject("select task_status from task where id = ?", String.class, failed.taskId()))
                .isEqualTo("FAILED");

        // 超过时限没有回调的转写按超时失败收尾
        jdbc.update("update source set updated_at = timestamp '2020-01-01 00:00:00' where id = ?", stale.sourceId());
        transcriptionService.failStaleTranscriptions();
        assertThat(jdbc.queryForObject("select parse_status from source where id = ?", String.class, stale.sourceId()))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select error_message from task where id = ?", String.class, stale.taskId()))
                .contains("SOURCE_TRANSCRIPTION_TIMEOUT");
    }

    private org.springframework.test.web.servlet.ResultActions callback(Fixture fixture, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post("/internal/worker/source-transcriptions/{snapshotId}", fixture.snapshotId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    /** 先上传一份文本资料，再把原件和类型替换成音视频，模拟异步流水线里刚上传的录音。 */
    private Fixture pendingMediaSource(String fileName, String mimeType, byte[] media) throws Exception {
        Fixture fixture = pendingSource(fileName.replaceAll("\\.[a-z0-9]+$", ".md"), "# 占位\n\n录音占位内容。");
        String objectKey = jdbc.queryForObject("select object_key from source_snapshot where id = ?", String.class,
                fixture.snapshotId());
        storage.write("noteweave-source", objectKey, media);
        jdbc.update("""
                update file_object set mime_type = ? where id = (select file_object_id from source where id = ?)
                """, mimeType, fixture.sourceId());
        jdbc.update("update source set title = ? where id = ?", fileName, fixture.sourceId());
        return fixture;
    }

    private boolean chunk(Pipeline pipeline, Fixture fixture, JsonNode payload) {
        return pipeline.parse().chunkStage(fixture.workspaceId(), fixture.sourceId(), fixture.snapshotId(),
                payload.path("textObjectKey").asText(), payload.path("mimeType").asText(),
                payload.path("pageCount").asInt());
    }

    /** 通过上传接口建立资料，再把快照恢复到等待解析的状态，模拟异步流水线的起点。 */
    private Fixture pendingSource(String fileName, String markdown) throws Exception {
        String workspaceId = createWorkspace();
        String sourceId = upload(workspaceId, fileName, markdown);
        String snapshotId = jdbc.queryForObject("select id from source_snapshot where source_id = ?", String.class, sourceId);
        jdbc.update("""
                delete from source_window where source_chunk_id in (select id from source_chunk where source_snapshot_id = ?)
                """, snapshotId);
        jdbc.update("delete from source_chunk where source_snapshot_id = ?", snapshotId);
        jdbc.update("""
                update source_snapshot set parse_status = 'PENDING', index_status = 'PENDING', processing_stage = null
                where id = ?
                """, snapshotId);
        jdbc.update("""
                update source set status = 'PROCESSING', parse_status = 'PENDING', index_status = 'PENDING' where id = ?
                """, sourceId);
        String taskId = taskService.createTask(workspaceId, "SOURCE_PARSE", "SOURCE", sourceId, "PARSING", "资料解析与切片");
        taskService.startTask(taskId);
        return new Fixture(workspaceId, sourceId, snapshotId, taskId);
    }

    private Pipeline pipeline(EmbeddingClient embeddings) {
        NoteWeaveProperties.Elasticsearch es = properties.elasticsearch();
        NoteWeaveProperties withIndex = new NoteWeaveProperties(properties.storage(), properties.document(),
                properties.worker(), properties.kafka(),
                new NoteWeaveProperties.Elasticsearch(true, es.host(), es.port(), es.scheme(), es.indexPrefix(),
                        es.username(), es.password()),
                properties.embedding(), properties.rerank(), properties.llm());
        SourceMessagingMode async = () -> true;
        SourceParseService parse = new SourceParseService(jdbc, chunker, extractor, mapper, storage, async, withIndex,
                taskService, transactionManager, auditActorProvider, catalogVersionService, eventPublisher);
        RetrievalIndexManager indexManager = mock(RetrievalIndexManager.class);
        when(indexManager.resolveWriteIndex(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        RetrievalProjectionWriter writer = mock(RetrievalProjectionWriter.class);
        SourceRetrievalProjectionService projection = new SourceRetrievalProjectionService(jdbc, embeddings,
                projectionRepository, indexManager, writer, withIndex, tagCodec);
        SourceRetrievalProjectionCoordinator coordinator = new SourceRetrievalProjectionCoordinator(projection,
                finalizer, writer, indexManager, jdbc, transactionManager, taskService, new SnapshotEmbeddingStore(storage));
        SourceReprocessService reprocess = new SourceReprocessService(jdbc, mapper, accessGuard, taskService, async,
                sourceParsePort, storage, catalogVersionService, eventPublisher);
        return new Pipeline(parse, coordinator, reprocess, embeddings, writer);
    }

    private EmbeddingClient fakeEmbeddings() {
        int dimensions = properties.embedding().dimensions();
        EmbeddingClient client = mock(EmbeddingClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.embedDocuments(anyList())).thenAnswer(call -> {
            List<String> texts = call.getArgument(0);
            List<List<Float>> vectors = new ArrayList<>();
            for (int index = 0; index < texts.size(); index++) {
                vectors.add(new ArrayList<>(Collections.nCopies(dimensions, 0.01f * (index + 1))));
            }
            return new EmbeddingClient.EmbeddingResult(vectors, properties.embedding().model(), dimensions, 0);
        });
        return client;
    }

    private String stage(Fixture fixture) {
        return jdbc.queryForObject("select processing_stage from source_snapshot where id = ?", String.class,
                fixture.snapshotId());
    }

    private JsonNode lastPayload(String topic, String messageKey) throws Exception {
        List<String> payloads = jdbc.queryForList("""
                select payload_json from task_outbox where topic = ? and message_key = ? order by created_at desc, id desc
                """, String.class, topic, messageKey);
        assertThat(payloads).as("outbox message on %s", topic).isNotEmpty();
        return mapper.readTree(payloads.get(0));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String createWorkspace() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "four-stage", "description", "pipeline"))))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("workspace_id").asText();
    }

    private String upload(String workspaceId, String fileName, String markdown) throws Exception {
        byte[] content = markdown.getBytes(StandardCharsets.UTF_8);
        MvcResult init = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/uploads", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("file_name", fileName, "file_size", content.length,
                                "mime_type", "text/markdown", "chunk_size", 1024,
                                "total_chunks", (content.length + 1023) / 1024))))
                .andExpect(status().isOk()).andReturn();
        String uploadId = mapper.readTree(init.getResponse().getContentAsString()).path("data").path("upload_id").asText();
        // 多个分片，覆盖分片逐个写入临时文件的合并路径
        for (int index = 0; index * 1024 < content.length; index++) {
            byte[] part = java.util.Arrays.copyOfRange(content, index * 1024, Math.min(content.length, (index + 1) * 1024));
            mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, index)
                            .contentType(MediaType.APPLICATION_OCTET_STREAM).content(part))
                    .andExpect(status().isOk());
        }
        MvcResult complete = mockMvc.perform(post("/api/v2/uploads/{uploadId}/complete", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parse_status").value("PARSED"))
                .andReturn();
        return mapper.readTree(complete.getResponse().getContentAsString()).path("data").path("source_id").asText();
    }

    private record Fixture(String workspaceId, String sourceId, String snapshotId, String taskId) {
    }

    private record Pipeline(SourceParseService parse, SourceRetrievalProjectionCoordinator coordinator,
                            SourceReprocessService reprocess, EmbeddingClient embeddings,
                            RetrievalProjectionWriter writer) {
    }
}
