package com.noteweave.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskCommandPort;
import com.noteweave.security.AuditActorProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SourceParseService implements SourceParsePort {

    private static final Logger log = LoggerFactory.getLogger(SourceParseService.class);
    private static final String BUCKET_SOURCE = "noteweave-source";

    private final JdbcTemplate jdbcTemplate;
    private final DocumentChunker documentChunker;
    private final SourceDocumentTextExtractor documentTextExtractor;
    private final ObjectMapper objectMapper;
    private final ObjectStorage storage;
    private final SourceMessagingMode messagingMode;
    private final boolean elasticsearchEnabled;
    private final TaskCommandPort taskCommandPort;
    private final TransactionTemplate transactionTemplate;
    private final AuditActorProvider auditActorProvider;
    private final SourceCatalogVersionService sourceCatalogVersionService;
    private final ApplicationEventPublisher eventPublisher;
    private final SourceTopicNames topicNames;
    private SourceTranscriptionPort transcriptionPort;
    private SourceTranscriptionSubmissionService transcriptionSubmissions;

    public SourceParseService(JdbcTemplate jdbcTemplate, DocumentChunker documentChunker,
                              SourceDocumentTextExtractor documentTextExtractor, ObjectMapper objectMapper,
                              ObjectStorage storage, SourceMessagingMode messagingMode,
                              NoteWeaveProperties properties, TaskCommandPort taskCommandPort,
                              PlatformTransactionManager transactionManager, AuditActorProvider auditActorProvider,
                              SourceCatalogVersionService sourceCatalogVersionService,
                              ApplicationEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentChunker = documentChunker;
        this.documentTextExtractor = documentTextExtractor;
        this.objectMapper = objectMapper;
        this.storage = storage;
        this.messagingMode = messagingMode;
        this.elasticsearchEnabled = properties.elasticsearch().enabled();
        this.taskCommandPort = taskCommandPort;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.auditActorProvider = auditActorProvider;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
        this.eventPublisher = eventPublisher;
        this.topicNames = new SourceTopicNames(properties);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTranscriptionPort(SourceTranscriptionPort transcriptionPort) {
        this.transcriptionPort = transcriptionPort;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTranscriptionSubmissions(SourceTranscriptionSubmissionService submissions) {
        this.transcriptionSubmissions = submissions;
    }

    @Override
    public void parseAndIndex(String workspaceId, String sourceId, String snapshotId, byte[] bytes) {
        Path original = null;
        try {
            original = Files.createTempFile("noteweave-source-sync-", ".bin");
            Files.write(original, bytes);
            parseAndIndex(workspaceId, sourceId, snapshotId, original);
        } catch (IOException exception) {
            throw new BusinessException("SOURCE_DOCUMENT_READ_FAILED", "资料原件读取失败");
        } finally {
            deleteQuietly(original);
        }
    }

    @Override
    public void parseAndIndex(String workspaceId, String sourceId, String snapshotId, Path original) {
        SourceMeta sourceMeta = loadSourceMeta(workspaceId, sourceId, snapshotId, false);
        if (!sourceMeta.extractable()) {
            throw new SourceParseNoLongerProcessableException(sourceId, snapshotId);
        }
        if (SourceMediaTypes.isMedia(sourceMeta.mimeType())) {
            // 转写可能持续数分钟，只能在异步流水线里由 Worker 完成
            throw new BusinessException("SOURCE_MEDIA_REQUIRES_ASYNC", "音视频资料需要开启异步处理后才能转写");
        }
        SourceDocumentTextExtractor.ExtractedDocument extracted = extract(workspaceId, sourceId, snapshotId, sourceMeta, original);
        // 长时间的解析调用结束后重新锁定身份和状态，只有当前快照可以提交片段。
        transactionTemplate.executeWithoutResult(ignored -> {
            SourceMeta locked = loadSourceMetaForProcessing(workspaceId, sourceId, snapshotId);
            if (!locked.extractable() || !locked.sha256().equals(sourceMeta.sha256())) {
                throw new SourceParseNoLongerProcessableException(sourceId, snapshotId);
            }
            persistParsedDocument(workspaceId, sourceId, snapshotId, locked.sha256(), extracted);
            String sourceParseTaskId = findSourceParseTaskId(sourceId);
            if (messagingMode.isAsyncEnabled() && elasticsearchEnabled && sourceParseTaskId == null) {
                sourceParseTaskId = taskCommandPort.createTask(
                        workspaceId, "SOURCE_PARSE", "SOURCE", sourceId, "PARSING", "生成资料解析与索引");
                taskCommandPort.startTask(sourceParseTaskId);
            }
            writeChunksAndQueueEmbedding(workspaceId, sourceId, snapshotId, locked, extracted, sourceParseTaskId);
        });
    }

    /**
     * 切片：生成片段和阅读窗口、更新资料元数据，然后投递向量化阶段。
     * 异步模式下投递到独立的向量化主题；同步模式（开发和测试）沿用原来的检索投影消息并在提交后同步执行。
     */
    private void writeChunksAndQueueEmbedding(String workspaceId, String sourceId, String snapshotId,
                                              SourceMeta sourceMeta,
                                              SourceDocumentTextExtractor.ExtractedDocument extracted,
                                              String sourceParseTaskId) {
        String text = extracted.text();
        String actor = auditActorProvider.currentOrSystem("SOURCE_PARSE");
        boolean projectionEnabled = elasticsearchEnabled;
        List<DocumentChunker.DocumentChunk> chunks = documentChunker.chunk(extracted);
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = Ids.newId();
            DocumentChunker.DocumentChunk chunk = chunks.get(i);
            jdbcTemplate.update("""
                    insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no, heading, content,
                                             token_estimate, location_info, page_start, page_end, structure_metadata_json,
                                             text_origin, ocr_confidence)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, chunkId, workspaceId, sourceId, snapshotId, i, chunk.headingPath(), chunk.content(),
                    Math.max(1, chunk.tokens()), chunk.location(i), chunk.pageStart(), chunk.pageEnd(),
                    chunk.structureMetadata() == null ? null : writeJson(chunk.structureMetadata()),
                    chunk.textOrigin() == null ? null : chunk.textOrigin().name(), chunk.ocrConfidence());
            List<String> windows = documentChunker.windows(chunk);
            for (int windowNo = 0; windowNo < windows.size(); windowNo++) {
                jdbcTemplate.update("""
                        insert into source_window(id, source_chunk_id, window_no, content, location_info)
                        values (?, ?, ?, ?, ?)
                        """, Ids.newId(), chunkId, windowNo, windows.get(windowNo), chunk.windowLocation(i, windowNo));
            }
        }

        List<String> outline = outline(chunks);
        List<String> tags = deriveTags(sourceMeta.title(), sourceMeta.sourceType(), text, outline);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", sourceMeta.title());
        metadata.put("source_type", sourceMeta.sourceType());
        metadata.put("mime_type", extracted.mimeType());
        metadata.put("chunk_count", chunks.size());
        metadata.put("char_count", text.length());
        metadata.put("parser", extracted.parser());
        metadata.put("truncated", extracted.truncated());
        metadata.put("degradation", extracted.degradation());
        metadata.put("warnings", extracted.warnings());
        if (!extracted.pageSources().isEmpty()) metadata.put("page_sources", extracted.pageSources());
        if (extracted.pageCount() > 0) {
            metadata.put("page_count", extracted.pageCount());
        }
        if (!outline.isEmpty()) {
            metadata.put("outline", outline);
        }
        metadata.put("chunking", extracted.elements().isEmpty() ? "structure_aware_token_v2" : "typed_elements_token_v3");
        metadata.put("chunker_version", documentChunker.version());
        metadata.put("entry_strategy", "marginalia_structured_reading_funnel");
        jdbcTemplate.update("""
                update source
                set summary = ?, tags_json = ?, metadata_json = ?,
                    parse_status = 'PARSED', index_status = ?, status = ?,
                    updated_by = ?, updated_at = current_timestamp
                where id = ? and status <> 'DELETED'
                """, summarize(text), writeJson(tags), writeJson(metadata),
                projectionEnabled ? "INDEXING" : "DISABLED",
                projectionEnabled ? "PROCESSING" : "READY", actor, sourceId);
        jdbcTemplate.update("""
                update source_snapshot set parse_status = 'PARSED', index_status = ?, processing_stage = ?
                where id = ?
                """, projectionEnabled ? "INDEXING" : "DISABLED",
                projectionEnabled ? SourcePipelineStages.STAGE_EMBEDDING : SourcePipelineStages.STAGE_READY,
                snapshotId);
        if (!projectionEnabled) {
            jdbcTemplate.update("""
                    update source_chunk
                    set projection_status = 'NOT_PROJECTED', projected_at = null
                    where source_snapshot_id = ?
                    """, snapshotId);
            if (sourceParseTaskId != null) {
                taskCommandPort.completeTask(
                        sourceParseTaskId,
                        "PARSED_LOCAL_ONLY",
                        "资料解析已完成；Elasticsearch 已禁用，本地可读，未写入检索投影",
                        sourceId
                );
            }
            sourceCatalogVersionService.bump(workspaceId);
            return;
        }
        Map<String, Object> projectionPayload = new java.util.LinkedHashMap<>();
        if (sourceParseTaskId != null) {
            projectionPayload.put("taskId", sourceParseTaskId);
        }
        projectionPayload.put("workspaceId", workspaceId);
        projectionPayload.put("sourceId", sourceId);
        projectionPayload.put("sourceSnapshotId", snapshotId);
        String projectionOutboxId = Ids.newId();
        boolean asyncStages = messagingMode.isAsyncEnabled();
        if (asyncStages) {
            jdbcTemplate.update("update source_snapshot set embedding_task_id = ? where id = ? and source_id = ?",
                    sourceParseTaskId, snapshotId, sourceId);
        }
        if (asyncStages && sourceParseTaskId != null) {
            recordStage(sourceParseTaskId, SourcePipelineStages.STAGE_EMBEDDING,
                    "切片完成，共 " + chunks.size() + " 个片段，等待向量化");
        }
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, ?, ?, ?, 'READY')
                """, projectionOutboxId, sourceParseTaskId,
                asyncStages ? topicNames.embed() : topicNames.retrievalProjection(),
                snapshotId, writeJson(projectionPayload));
        if (!messagingMode.isAsyncEnabled()) {
            eventPublisher.publishEvent(new SynchronousRetrievalProjectionRequested(
                    projectionOutboxId, sourceParseTaskId, workspaceId, sourceId, snapshotId));
        }
        sourceCatalogVersionService.bump(workspaceId);
    }

    /**
     * 异步入口：从 MinIO 重新读取原始文件，再调用同步版 parseAndIndex。
     * 由 KafkaTaskConsumer.onSourceParse 调用。
     */
    public void parseAndIndexAsync(String workspaceId, String sourceId, String snapshotId) {
        parseAndIndexAsyncIfProcessable(workspaceId, sourceId, snapshotId);
    }

    /**
     * 异步解析阶段：把原件下载到临时文件，提取文本写入派生存储，再投递切片阶段。
     * 原件不整体读入内存，PDF 的解析缓冲也放在临时文件里。
     */
    public boolean parseAndIndexAsyncIfProcessable(String workspaceId, String sourceId, String snapshotId) {
        java.nio.file.Path original = null;
        try {
            if (!isProcessable(workspaceId, sourceId, snapshotId)) {
                log.info("Skip async source parse because source/snapshot is no longer processable: sourceId={}, snapshotId={}",
                        sourceId, snapshotId);
                return false;
            }
            SourceMeta meta = loadSourceMeta(workspaceId, sourceId, snapshotId, false);
            if (!meta.extractable()) return false;
            if (SourceMediaTypes.isMedia(meta.mimeType()) && transcriptionSubmissions != null) {
                return submitTranscription(workspaceId, sourceId, snapshotId, meta, null);
            }
            original = java.nio.file.Files.createTempFile("noteweave-source-", ".bin");
            storage.readToFile(BUCKET_SOURCE, meta.objectKey(), original);
            if (SourceMediaTypes.isMedia(meta.mimeType())) {
                return submitTranscription(workspaceId, sourceId, snapshotId, meta, original);
            }
            SourceDocumentTextExtractor.ExtractedDocument extracted =
                    extract(workspaceId, sourceId, snapshotId, meta, original);
            String textKey = SourcePipelineStages.extractedTextKey(workspaceId, sourceId, snapshotId);
            Boolean queued = transactionTemplate.execute(ignored -> {
                SourceMeta locked = loadSourceMetaForProcessing(workspaceId, sourceId, snapshotId);
                if (!locked.extractable() || !locked.sha256().equals(meta.sha256())) return false;
                persistParsedDocument(workspaceId, sourceId, snapshotId, locked.sha256(), extracted);
                return queueChunkStage(workspaceId, sourceId, snapshotId, textKey, extracted);
            });
            if (Boolean.TRUE.equals(queued)) {
                log.info("SourceParseService extracted text: sourceId={}, snapshotId={}, chars={}",
                        sourceId, snapshotId, extracted.text().length());
            }
            return Boolean.TRUE.equals(queued);
        } catch (SourceParseNoLongerProcessableException ex) {
            log.info("Skip async source parse after concurrent source/snapshot change: sourceId={}, snapshotId={}",
                    sourceId, snapshotId);
            return false;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("SourceParseService async parse failed for sourceId={}: {}", sourceId, ex.getMessage(), ex);
            throw new IllegalStateException("async source parse failed for sourceId=" + sourceId, ex);
        } finally {
            deleteQuietly(original);
        }
    }

    private boolean queueChunkStage(String workspaceId, String sourceId, String snapshotId, String textKey,
                                    SourceDocumentTextExtractor.ExtractedDocument extracted) {
        SourceMeta meta = loadSourceMetaForProcessing(workspaceId, sourceId, snapshotId);
        if (!meta.extractable()) return false;
        int moved = jdbcTemplate.update("""
                update source_snapshot set processing_stage = ?
                where id = ? and parse_status = 'PENDING'
                  and (processing_stage is null or processing_stage = ?)
                """, SourcePipelineStages.STAGE_CHUNKING, snapshotId, SourcePipelineStages.STAGE_EXTRACTING);
        if (moved != 1) {
            return false;
        }
        return insertChunkOutbox(workspaceId, sourceId, snapshotId, textKey, extracted,
                "解析完成，提取 " + extracted.text().length() + " 个字符"
                        + (extracted.pageCount() > 0 ? "（" + extracted.pageCount() + " 页）" : "") + "，等待切片");
    }

    /**
     * 音视频资料的解析阶段：原件交给 Worker 转写，快照进入转写阶段，等待 Worker 回调文字稿。
     * 生产环境先持久化阶段和待提交记录，再由调度器发送，快速回调也能读取正确阶段。
     */
    private boolean submitTranscription(String workspaceId, String sourceId, String snapshotId, SourceMeta meta,
                                        java.nio.file.Path original) {
        if (transcriptionPort == null && transcriptionSubmissions == null) {
            throw new BusinessException("SOURCE_TRANSCRIPTION_UNAVAILABLE", "转写服务未配置，无法处理音视频资料");
        }
        // 保留手动构造旧服务时的兼容入口；生产环境使用持久提交调度器。
        if (transcriptionSubmissions == null) {
            transcriptionPort.submit(new SourceTranscriptionPort.Request(
                    workspaceId, sourceId, snapshotId, meta.title(), meta.mimeType()), original);
        }
        Boolean moved = transactionTemplate.execute(ignored -> {
            SourceMeta locked = loadSourceMetaForProcessing(workspaceId, sourceId, snapshotId);
            if (!locked.extractable()) return false;
            int updated = jdbcTemplate.update("""
                    update source_snapshot set processing_stage = ?
                    where id = ? and parse_status = 'PENDING'
                      and (processing_stage is null or processing_stage = ?)
                    """, SourcePipelineStages.STAGE_TRANSCRIBING, snapshotId, SourcePipelineStages.STAGE_EXTRACTING);
            if (updated != 1) return false;
            // updated_at 记录进入转写阶段的时间，供超时检查使用
            jdbcTemplate.update("update source set updated_at = current_timestamp where id = ?", sourceId);
            String taskId = findSourceParseTaskId(sourceId);
            if (transcriptionSubmissions != null) {
                transcriptionSubmissions.enqueue(new SourceTranscriptionPort.Request(
                        workspaceId, sourceId, snapshotId, locked.title(), locked.mimeType(), taskId),
                        locked.objectKey(), locked.sha256());
            }
            if (taskId != null) {
                recordStage(taskId, SourcePipelineStages.STAGE_TRANSCRIBING, transcriptionSubmissions == null
                        ? "已提交音视频转写，等待 MCP 转写工具返回文字稿"
                        : "等待转写服务接纳并返回文字稿");
            }
            sourceCatalogVersionService.bump(workspaceId);
            return true;
        });
        return Boolean.TRUE.equals(moved);
    }

    /**
     * Worker 回调转写结果：文字稿写入派生存储并投递切片阶段。快照不在转写阶段时忽略（重复回调）。
     */
    public boolean acceptTranscript(String workspaceId, String sourceId, String snapshotId, String transcript,
                                    Double durationSeconds) {
        return acceptTranscript(workspaceId, sourceId, snapshotId, transcript, durationSeconds, null);
    }

    public boolean acceptTranscript(String workspaceId, String sourceId, String snapshotId, String transcript,
                                    Double durationSeconds, Integer correctedLineCount) {
        String textKey = SourcePipelineStages.extractedTextKey(workspaceId, sourceId, snapshotId);
        storage.write(SourcePipelineStages.BUCKET_DERIVED, textKey,
                transcript.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Boolean queued = transactionTemplate.execute(ignored -> {
            SourceMeta meta = loadSourceMetaForProcessing(workspaceId, sourceId, snapshotId);
            if (!meta.processable()) return false;
            int moved = jdbcTemplate.update("""
                    update source_snapshot set processing_stage = ?
                    where id = ? and parse_status = 'PENDING' and processing_stage = ?
                    """, SourcePipelineStages.STAGE_CHUNKING, snapshotId, SourcePipelineStages.STAGE_TRANSCRIBING);
            if (moved != 1) return false;
            String duration = durationSeconds == null || durationSeconds <= 0 ? ""
                    : "（时长约 " + Math.max(1, Math.round(durationSeconds / 60)) + " 分钟）";
            return insertChunkOutbox(workspaceId, sourceId, snapshotId, textKey,
                    new SourceDocumentTextExtractor.ExtractedDocument(transcript, meta.mimeType(), 0),
                    "转写完成" + duration + "，文字稿共 " + transcript.length() + " 个字符"
                            + (correctedLineCount != null && correctedLineCount > 0
                            ? "，大模型校对修正 " + correctedLineCount + " 行" : "") + "，等待切片");
        });
        return Boolean.TRUE.equals(queued);
    }

    /** 最近一次资料解析任务，供转写失败时收尾。 */
    public String latestParseTaskId(String sourceId) {
        return findSourceParseTaskId(sourceId);
    }

    private boolean insertChunkOutbox(String workspaceId, String sourceId, String snapshotId, String textKey,
                                      SourceDocumentTextExtractor.ExtractedDocument extracted, String stageMessage) {
        String taskId = findSourceParseTaskId(sourceId);
        if (taskId != null) {
            recordStage(taskId, SourcePipelineStages.STAGE_CHUNKING, stageMessage);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (taskId != null) payload.put("taskId", taskId);
        payload.put("workspaceId", workspaceId);
        payload.put("sourceId", sourceId);
        payload.put("snapshotId", snapshotId);
        payload.put("textObjectKey", textKey);
        payload.put("mimeType", extracted.mimeType());
        payload.put("pageCount", extracted.pageCount());
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, ?, ?, ?, 'READY')
                """, Ids.newId(), taskId, topicNames.chunk(), snapshotId, writeJson(payload));
        sourceCatalogVersionService.bump(workspaceId);
        return true;
    }

    /**
     * 异步切片阶段：读取解析阶段写入的文本，生成片段和阅读窗口，再投递向量化阶段。
     * 快照不在切片阶段（已处理、已删除或被重新处理）时返回 false，消息直接确认。
     */
    public boolean chunkStage(String workspaceId, String sourceId, String snapshotId, String textObjectKey,
                              String mimeType, int pageCount) {
        return Boolean.TRUE.equals(transactionTemplate.execute(ignored ->
                chunkStageLocked(workspaceId, sourceId, snapshotId, textObjectKey, mimeType, pageCount)));
    }

    private boolean chunkStageLocked(String workspaceId, String sourceId, String snapshotId, String textObjectKey,
                                     String mimeType, int pageCount) {
        SourceMeta meta = loadSourceMetaForProcessing(workspaceId, sourceId, snapshotId);
        String stage = meta.processingStage();
        if (!meta.processable() || !SourcePipelineStages.STAGE_CHUNKING.equals(stage)) {
            log.info("Skip source chunk stage: sourceId={}, snapshotId={}, stage={}", sourceId, snapshotId, stage);
            return false;
        }
        String expectedTextKey = SourcePipelineStages.extractedTextKey(workspaceId, sourceId, snapshotId);
        if (!expectedTextKey.equals(textObjectKey)) {
            throw new BusinessException("SOURCE_PARSED_DOCUMENT_IDENTITY_MISMATCH", "切片文本对象不属于当前快照");
        }
        SourceDocumentTextExtractor.ExtractedDocument extracted = loadParsedDocument(
                workspaceId, sourceId, snapshotId, meta, expectedTextKey, mimeType, pageCount);
        writeChunksAndQueueEmbedding(workspaceId, sourceId, snapshotId, meta,
                extracted,
                findSourceParseTaskId(sourceId));
        return true;
    }

    private SourceDocumentTextExtractor.ExtractedDocument extract(String workspaceId, String sourceId, String snapshotId,
                                                                  SourceMeta meta, Path original) {
        return documentTextExtractor.extract(new SourceDocumentParserPort.Request(workspaceId, sourceId, snapshotId,
                meta.title(), meta.mimeType(), meta.sha256(), 20_000_000), original);
    }

    static String parsedDocumentKey(String workspaceId, String sourceId, String snapshotId) {
        return "workspace/%s/source/%s/snapshot/%s/parsed-document.json".formatted(workspaceId, sourceId, snapshotId);
    }

    /** 结构文档与兼容文本均按快照隔离写入，失败时不会推进数据库阶段。 */
    private void persistParsedDocument(String workspaceId, String sourceId, String snapshotId, String sha256,
                                       SourceDocumentTextExtractor.ExtractedDocument document) {
        Path json = null, text = null;
        try {
            json = Files.createTempFile("noteweave-parsed-", ".json");
            text = Files.createTempFile("noteweave-extracted-", ".txt");
            objectMapper.writeValue(json.toFile(), new StoredParsedDocument(1, workspaceId, sourceId, snapshotId, sha256, document));
            Files.writeString(text, document.text(), StandardCharsets.UTF_8);
            storage.writeFile(SourcePipelineStages.BUCKET_DERIVED, parsedDocumentKey(workspaceId, sourceId, snapshotId), json);
            storage.writeFile(SourcePipelineStages.BUCKET_DERIVED,
                    SourcePipelineStages.extractedTextKey(workspaceId, sourceId, snapshotId), text);
        } catch (IOException exception) {
            throw new BusinessException("SOURCE_PARSED_DOCUMENT_WRITE_FAILED", "解析结构文档写入失败");
        } finally {
            deleteQuietly(json);
            deleteQuietly(text);
        }
    }

    private SourceDocumentTextExtractor.ExtractedDocument loadParsedDocument(String workspaceId, String sourceId,
                                                                           String snapshotId, SourceMeta meta,
                                                                           String textKey, String mimeType, int pageCount) {
        String key = parsedDocumentKey(workspaceId, sourceId, snapshotId);
        // 转写稿继续使用原来的文字稿路径，不混入先前的文档结构。
        if (!SourceMediaTypes.isMedia(meta.mimeType()) && storage.exists(SourcePipelineStages.BUCKET_DERIVED, key)) {
            Path file = null;
            try {
                file = Files.createTempFile("noteweave-parsed-read-", ".json");
                storage.readToFile(SourcePipelineStages.BUCKET_DERIVED, key, file);
                try (InputStream input = new HttpSourceDocumentParserClient.BoundedInputStream(Files.newInputStream(file),
                        256L * 1024 * 1024)) {
                    StoredParsedDocument stored = objectMapper.readValue(input, StoredParsedDocument.class);
                    var document = stored.document();
                    if (stored.schemaVersion() != 1 || !workspaceId.equals(stored.workspaceId())
                            || !sourceId.equals(stored.sourceId()) || !snapshotId.equals(stored.snapshotId())
                            || !meta.sha256().equals(stored.sha256()) || document == null
                            || document.text() == null || document.text().length() > 20_000_000
                            || !meta.mimeType().equals(document.mimeType())) {
                        throw new BusinessException("SOURCE_PARSED_DOCUMENT_IDENTITY_MISMATCH", "解析结构文档与当前快照不一致");
                    }
                    return document;
                }
            } catch (IOException exception) {
                throw new BusinessException("SOURCE_PARSED_DOCUMENT_INVALID", "解析结构文档不可读取或结构无效");
            } finally {
                deleteQuietly(file);
            }
        }
        if (!meta.mimeType().equals(mimeType)) {
            throw new BusinessException("SOURCE_PARSED_DOCUMENT_IDENTITY_MISMATCH", "切片消息资料类型与当前快照不一致");
        }
        Path file = null;
        try {
            file = Files.createTempFile("noteweave-legacy-text-", ".txt");
            storage.readToFile(SourcePipelineStages.BUCKET_DERIVED, textKey, file);
            StringBuilder text = new StringBuilder();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    if (text.length() + read > 20_000_000) throw new BusinessException(
                            "SOURCE_PARSED_DOCUMENT_INVALID", "旧解析文本超出字符上限");
                    text.append(buffer, 0, read);
                }
            }
            return new SourceDocumentTextExtractor.ExtractedDocument(text.toString(), mimeType, pageCount);
        } catch (IOException exception) {
            throw new BusinessException("SOURCE_PARSED_DOCUMENT_INVALID", "旧解析文本读取失败");
        } finally {
            deleteQuietly(file);
        }
    }

    public record StoredParsedDocument(int schemaVersion, String workspaceId, String sourceId, String snapshotId,
                                       String sha256, SourceDocumentTextExtractor.ExtractedDocument document) {}

    private void recordStage(String taskId, String stage, String message) {
        try {
            taskCommandPort.recordStage(taskId, stage, message);
        } catch (RuntimeException ex) {
            // 进度记录失败不影响资料处理本身
            log.warn("Failed to record source pipeline stage: taskId={}, stage={}: {}", taskId, stage, ex.getMessage());
        }
    }

    private static void deleteQuietly(java.nio.file.Path file) {
        if (file == null) return;
        try {
            java.nio.file.Files.deleteIfExists(file);
        } catch (java.io.IOException ex) {
            log.warn("Failed to delete source temp file {}", file);
        }
    }

    public boolean isProcessable(String workspaceId, String sourceId, String snapshotId) {
        return assessProcessability(workspaceId, sourceId, snapshotId).processable();
    }

    public SourceParseAssessment assessProcessability(String workspaceId, String sourceId, String snapshotId) {
        return jdbcTemplate.query("""
                select s.status as source_status, ss.parse_status as snapshot_parse_status,
                       ss.index_status as snapshot_index_status, ss.processing_stage, ss.version_no,
                       (select max(current_snapshot.version_no) from source_snapshot current_snapshot
                        where current_snapshot.source_id = s.id) as latest_version
                from source s
                left join source_snapshot ss on ss.id = ? and ss.source_id = s.id
                where s.id = ? and s.workspace_id = ?
                """, rs -> {
            if (!rs.next()) {
                return new SourceParseAssessment(
                        SourceParseDisposition.TARGET_MISSING, "MISSING", "MISSING");
            }
            String sourceStatus = rs.getString("source_status");
            String snapshotParseStatus = rs.getString("snapshot_parse_status");
            if (snapshotParseStatus == null) {
                return new SourceParseAssessment(
                        SourceParseDisposition.TARGET_MISSING, sourceStatus, "MISSING");
            }
            if ("DELETED".equals(sourceStatus) || "DELETED".equals(snapshotParseStatus)) {
                return new SourceParseAssessment(
                        SourceParseDisposition.TARGET_DELETED, sourceStatus, snapshotParseStatus);
            }
            if (rs.getInt("version_no") != rs.getInt("latest_version")) {
                return new SourceParseAssessment(SourceParseDisposition.ALREADY_HANDLED, sourceStatus, snapshotParseStatus);
            }
            String stage = rs.getString("processing_stage");
            boolean laterStage = stage != null && !SourcePipelineStages.STAGE_EXTRACTING.equals(stage)
                    && !SourcePipelineStages.STAGE_READY.equals(stage);
            if ("PENDING".equals(snapshotParseStatus) && !laterStage) {
                return new SourceParseAssessment(
                        SourceParseDisposition.PROCESSABLE, sourceStatus, snapshotParseStatus);
            }
            if (laterStage && !"FAILED".equals(snapshotParseStatus)
                    && !"FAILED".equals(rs.getString("snapshot_index_status"))) {
                return new SourceParseAssessment(
                        SourceParseDisposition.IN_LATER_STAGE, sourceStatus, snapshotParseStatus);
            }
            return new SourceParseAssessment(
                    SourceParseDisposition.ALREADY_HANDLED, sourceStatus, snapshotParseStatus);
        }, snapshotId, sourceId, workspaceId);
    }

    private SourceMeta loadSourceMetaForProcessing(String workspaceId, String sourceId, String snapshotId) {
        return loadSourceMeta(workspaceId, sourceId, snapshotId, true);
    }

    private SourceMeta loadSourceMeta(String workspaceId, String sourceId, String snapshotId, boolean lock) {
        return jdbcTemplate.query("""
                select s.title, s.source_type, s.status, ss.parse_status, ss.processing_stage,
                       ss.sha256, ss.object_key, ss.version_no,
                       (select max(current_snapshot.version_no) from source_snapshot current_snapshot
                        where current_snapshot.source_id = s.id) as latest_version,
                       coalesce(fo.mime_type, 'text/markdown') as mime_type
                from source s
                join source_snapshot ss on ss.id = ? and ss.source_id = s.id
                left join file_object fo on fo.id = ss.file_object_id
                where s.id = ? and s.workspace_id = ?
                """ + (lock ? " for update" : ""), rs -> {
            if (!rs.next()) {
                throw new BusinessException("SOURCE_NOT_FOUND", "资料不存在");
            }
            boolean processable = !"DELETED".equals(rs.getString("status"))
                    && "PENDING".equals(rs.getString("parse_status"))
                    && rs.getInt("version_no") == rs.getInt("latest_version");
            return new SourceMeta(
                    rs.getString("title"),
                    rs.getString("source_type"),
                    rs.getString("mime_type").split(";", 2)[0].trim().toLowerCase(Locale.ROOT),
                    processable, rs.getString("sha256"), rs.getString("object_key"), rs.getString("processing_stage")
            );
        }, snapshotId, sourceId, workspaceId);
    }

    private String findSourceParseTaskId(String sourceId) {
        List<String> taskIds = jdbcTemplate.queryForList("""
                select id from task where task_type = 'SOURCE_PARSE' and target_id = ?
                order by created_at desc, id desc limit 1
                """, String.class, sourceId);
        return taskIds.isEmpty() ? null : taskIds.get(0);
    }

    private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern ASCII_TERM = Pattern.compile("[A-Za-z][A-Za-z0-9.+#-]{1,30}");
    private static final Pattern HEADING_NUMBER = Pattern.compile(
            "^(第[一二三四五六七八九十百零〇\\d]+[章节篇部]|[一二三四五六七八九十]+[、.．]|[（(][一二三四五六七八九十]+[)）]|\\d{1,2}(\\.\\d{1,2}){0,3}[.、．]?)\\s*");
    /** 目录行：较短、以页码结尾，例如 "2 知识点 2" 或 "第一章 概述 ........ 5"。 */
    private static final Pattern TOC_LINE = Pattern.compile("^.{1,60}?[\\s.·…]+\\d{1,4}$");
    private static final Set<String> TERM_STOPWORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "from", "are", "was", "were", "has", "have", "not",
            "but", "can", "will", "you", "your", "our", "its", "into", "than", "then", "also", "such", "use",
            "http", "https", "www", "com");

    /** 摘要：跳过标题、代码块和表格，去掉 Markdown 标记与转写时间戳，按句子截取开头约 360 字。 */
    private String summarize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        boolean inFence = false;
        for (String raw : text.replace('\f', '\n').split("\n")) {
            String line = raw.strip();
            if (line.startsWith("```") || line.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence || line.isEmpty() || line.startsWith("#") || line.startsWith("|")
                    || "目录".equals(line) || "Contents".equalsIgnoreCase(line) || TOC_LINE.matcher(line).matches()) {
                continue;
            }
            line = MARKDOWN_LINK.matcher(line).replaceAll("$1")
                    .replaceAll("^\\[\\d{1,2}:\\d{2}(:\\d{2})?\\]\\s*", "")
                    .replaceAll("^([>*+-]|\\d+[.)])\\s+", "")
                    .replaceAll("[*_`]+", "");
            if (!line.isBlank()) {
                if (!body.isEmpty()) body.append(' ');
                body.append(line);
            }
            if (body.length() > 600) break;
        }
        String normalized = body.toString().replaceAll("\\s+", " ").strip();
        if (normalized.isEmpty()) {
            normalized = text.replaceAll("\\s+", " ").strip();
        }
        if (normalized.length() <= 360) {
            return normalized;
        }
        String head = normalized.substring(0, 360);
        int sentenceEnd = Math.max(Math.max(head.lastIndexOf('。'), head.lastIndexOf('！')),
                Math.max(head.lastIndexOf('？'), head.lastIndexOf(". ")));
        return sentenceEnd >= 180 ? head.substring(0, sentenceEnd + 1) : head.substring(0, 359) + "...";
    }

    /** 资料大纲：按出现顺序去重的章节路径，最多 30 条，供资料级召回和界面展示。 */
    private static List<String> outline(List<DocumentChunker.DocumentChunk> chunks) {
        Set<String> paths = new LinkedHashSet<>();
        for (DocumentChunker.DocumentChunk chunk : chunks) {
            if (chunk.headingPath() != null && !chunk.headingPath().isBlank()) {
                paths.add(chunk.headingPath());
            }
            if (paths.size() >= 30) break;
        }
        return new ArrayList<>(paths);
    }

    /**
     * 标签：资料类型、标题中的词、一级章节名（去掉编号），以及正文里反复出现的英文术语。
     * 没有中文分词器时，不把整段连续汉字当成一个标签。
     */
    private List<String> deriveTags(String title, String sourceType, String text, List<String> outline) {
        Set<String> tags = new LinkedHashSet<>();
        if (sourceType != null && !sourceType.isBlank()) {
            tags.add(sourceType.toLowerCase(Locale.ROOT));
        }
        if (title != null) {
            String stem = title.replaceAll("\\.[A-Za-z0-9]{1,5}$", "");
            for (String part : stem.toLowerCase(Locale.ROOT).split("[^\\p{IsHan}a-z0-9]+")) {
                if (part.length() >= 2 && part.length() <= 12) tags.add(part);
            }
        }
        for (String path : outline) {
            String top = path.split(" > ", 2)[0];
            String name = HEADING_NUMBER.matcher(top).replaceFirst("").strip();
            // 带数字或时间的章节名（如 "画面 01 ·00:00"）是编号而不是主题，不作为标签
            if (name.length() >= 2 && name.length() <= 16 && !name.matches(".*\\d.*")) tags.add(name);
            if (tags.size() >= 8) break;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher matcher = ASCII_TERM.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String term = matcher.group().replaceAll("[.-]+$", "").toLowerCase(Locale.ROOT);
            if (term.length() >= 2 && !TERM_STOPWORDS.contains(term)) counts.merge(term, 1, Integer::sum);
        }
        counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= 2)
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(6)
                .forEach(entry -> tags.add(entry.getKey()));
        // 短资料里高频术语很少，用开头 800 字中的英文术语按出现顺序补足
        String head = text == null ? "" : text.substring(0, Math.min(text.length(), 800));
        Matcher headTerms = ASCII_TERM.matcher(head);
        while (tags.size() < 12 && headTerms.find()) {
            String term = headTerms.group().replaceAll("[.-]+$", "").toLowerCase(Locale.ROOT);
            if (term.length() >= 2 && !TERM_STOPWORDS.contains(term)) tags.add(term);
        }
        return new ArrayList<>(tags).stream().limit(12).toList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("JSON_WRITE_FAILED", "资料元数据序列化失败");
        }
    }

    private record SourceMeta(String title, String sourceType, String mimeType, boolean processable,
                              String sha256, String objectKey, String processingStage) {
        boolean extractable() {
            return processable && (processingStage == null || SourcePipelineStages.STAGE_EXTRACTING.equals(processingStage));
        }
    }

    public record SourceParseAssessment(
            SourceParseDisposition disposition,
            String sourceStatus,
            String snapshotParseStatus
    ) {
        public boolean processable() {
            return disposition == SourceParseDisposition.PROCESSABLE;
        }
    }

    public enum SourceParseDisposition {
        PROCESSABLE,
        TARGET_DELETED,
        ALREADY_HANDLED,
        TARGET_MISSING,
        /** 快照已进入切片、向量化或索引阶段，由后续阶段负责任务收尾。 */
        IN_LATER_STAGE
    }

    public record SynchronousRetrievalProjectionRequested(
            String outboxId,
            String taskId,
            String workspaceId,
            String sourceId,
            String sourceSnapshotId
    ) {
    }

    private static final class SourceParseNoLongerProcessableException extends RuntimeException {

        private SourceParseNoLongerProcessableException(String sourceId, String snapshotId) {
            super("source/snapshot is no longer processable: sourceId=" + sourceId + ", snapshotId=" + snapshotId);
        }
    }
}
