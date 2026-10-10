package com.noteweave.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskCommandPort;
import com.noteweave.security.AuditActorProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SourceParseService implements SourceParsePort {

    private static final Logger log = LoggerFactory.getLogger(SourceParseService.class);
    private static final int WINDOW_CHARS = 320;
    private static final int WINDOW_OVERLAP = 80;
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
    private SourceTranscriptionPort transcriptionPort;

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
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTranscriptionPort(SourceTranscriptionPort transcriptionPort) {
        this.transcriptionPort = transcriptionPort;
    }

    @Transactional
    public void parseAndIndex(String workspaceId, String sourceId, String snapshotId, byte[] bytes) {
        SourceMeta sourceMeta = loadSourceMetaForProcessing(workspaceId, sourceId, snapshotId);
        if (!sourceMeta.processable()) {
            throw new SourceParseNoLongerProcessableException(sourceId, snapshotId);
        }
        if (SourceMediaTypes.isMedia(sourceMeta.mimeType())) {
            // 转写可能持续数分钟，只能在异步流水线里由 Worker 完成
            throw new BusinessException("SOURCE_MEDIA_REQUIRES_ASYNC", "音视频资料需要开启异步处理后才能转写");
        }
        SourceDocumentTextExtractor.ExtractedDocument extracted = documentTextExtractor.extract(
                sourceMeta.title(), sourceMeta.mimeType(), bytes);
        String sourceParseTaskId = findSourceParseTaskId(sourceId);
        if (messagingMode.isAsyncEnabled() && elasticsearchEnabled && sourceParseTaskId == null) {
            sourceParseTaskId = taskCommandPort.createTask(
                    workspaceId, "SOURCE_PARSE", "SOURCE", sourceId, "PARSING", "生成资料解析与索引");
            taskCommandPort.startTask(sourceParseTaskId);
        }
        writeChunksAndQueueEmbedding(workspaceId, sourceId, snapshotId, sourceMeta, extracted, sourceParseTaskId);
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
        List<String> chunks = documentChunker.chunk(text);
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = Ids.newId();
            String content = chunks.get(i);
            jdbcTemplate.update("""
                    insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no, heading, content, token_estimate, location_info)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, chunkId, workspaceId, sourceId, snapshotId, i, "片段 " + (i + 1), content,
                    Math.max(1, content.length() / 2), "chunk:" + i);
            List<String> windows = buildReadWindows(content);
            for (int windowNo = 0; windowNo < windows.size(); windowNo++) {
                jdbcTemplate.update("""
                        insert into source_window(id, source_chunk_id, window_no, content, location_info)
                        values (?, ?, ?, ?, ?)
                        """, Ids.newId(), chunkId, windowNo, windows.get(windowNo), "chunk:" + i + "/window:" + windowNo);
            }
        }

        List<String> tags = deriveTags(sourceMeta.title(), sourceMeta.sourceType(), text);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", sourceMeta.title());
        metadata.put("source_type", sourceMeta.sourceType());
        metadata.put("mime_type", extracted.mimeType());
        metadata.put("chunk_count", chunks.size());
        metadata.put("char_count", text.length());
        if (extracted.pageCount() > 0) {
            metadata.put("page_count", extracted.pageCount());
        }
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
        if (asyncStages && sourceParseTaskId != null) {
            recordStage(sourceParseTaskId, SourcePipelineStages.STAGE_EMBEDDING,
                    "切片完成，共 " + chunks.size() + " 个片段，等待向量化");
        }
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, ?, ?, ?, 'READY')
                """, projectionOutboxId, sourceParseTaskId,
                asyncStages ? SourcePipelineStages.TOPIC_EMBED : "noteweave.retrieval.projection",
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
            String objectKey = jdbcTemplate.queryForObject("""
                    select object_key from source_snapshot where id = ?
                    """, String.class, snapshotId);
            SourceMeta meta = loadSourceMeta(workspaceId, sourceId, snapshotId, false);
            original = java.nio.file.Files.createTempFile("noteweave-source-", ".bin");
            storage.readToFile(BUCKET_SOURCE, objectKey, original);
            if (SourceMediaTypes.isMedia(meta.mimeType())) {
                return submitTranscription(workspaceId, sourceId, snapshotId, meta, original);
            }
            SourceDocumentTextExtractor.ExtractedDocument extracted =
                    documentTextExtractor.extract(meta.title(), meta.mimeType(), original);
            String textKey = SourcePipelineStages.extractedTextKey(workspaceId, sourceId, snapshotId);
            storage.write(SourcePipelineStages.BUCKET_DERIVED, textKey,
                    extracted.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Boolean queued = transactionTemplate.execute(ignored ->
                    queueChunkStage(workspaceId, sourceId, snapshotId, textKey, extracted));
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
        int moved = jdbcTemplate.update("""
                update source_snapshot set processing_stage = ?
                where id = ? and parse_status = 'PENDING'
                  and (processing_stage is null or processing_stage = ?)
                """, SourcePipelineStages.STAGE_CHUNKING, snapshotId, SourcePipelineStages.STAGE_EXTRACTING);
        if (!meta.processable() || moved != 1) {
            return false;
        }
        return insertChunkOutbox(workspaceId, sourceId, snapshotId, textKey, extracted,
                "解析完成，提取 " + extracted.text().length() + " 个字符"
                        + (extracted.pageCount() > 0 ? "（" + extracted.pageCount() + " 页）" : "") + "，等待切片");
    }

    /**
     * 音视频资料的解析阶段：原件交给 Worker 转写，快照进入转写阶段，等待 Worker 回调文字稿。
     * 先提交再改状态：提交失败时快照仍停在解析阶段，Kafka 重试会重新提交。
     */
    private boolean submitTranscription(String workspaceId, String sourceId, String snapshotId, SourceMeta meta,
                                        java.nio.file.Path original) {
        if (transcriptionPort == null) {
            throw new BusinessException("SOURCE_TRANSCRIPTION_UNAVAILABLE", "转写服务未配置，无法处理音视频资料");
        }
        transcriptionPort.submit(new SourceTranscriptionPort.Request(
                workspaceId, sourceId, snapshotId, meta.title(), meta.mimeType()), original);
        Boolean moved = transactionTemplate.execute(ignored -> {
            SourceMeta locked = loadSourceMetaForProcessing(workspaceId, sourceId, snapshotId);
            int updated = jdbcTemplate.update("""
                    update source_snapshot set processing_stage = ?
                    where id = ? and parse_status = 'PENDING'
                      and (processing_stage is null or processing_stage = ?)
                    """, SourcePipelineStages.STAGE_TRANSCRIBING, snapshotId, SourcePipelineStages.STAGE_EXTRACTING);
            if (!locked.processable() || updated != 1) return false;
            // updated_at 记录进入转写阶段的时间，供超时检查使用
            jdbcTemplate.update("update source set updated_at = current_timestamp where id = ?", sourceId);
            String taskId = findSourceParseTaskId(sourceId);
            if (taskId != null) {
                recordStage(taskId, SourcePipelineStages.STAGE_TRANSCRIBING, "已提交音视频转写，等待 MCP 转写工具返回文字稿");
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
            int moved = jdbcTemplate.update("""
                    update source_snapshot set processing_stage = ?
                    where id = ? and parse_status = 'PENDING' and processing_stage = ?
                    """, SourcePipelineStages.STAGE_CHUNKING, snapshotId, SourcePipelineStages.STAGE_TRANSCRIBING);
            if (!meta.processable() || moved != 1) return false;
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
                """, Ids.newId(), taskId, SourcePipelineStages.TOPIC_CHUNK, snapshotId, writeJson(payload));
        sourceCatalogVersionService.bump(workspaceId);
        return true;
    }

    /**
     * 异步切片阶段：读取解析阶段写入的文本，生成片段和阅读窗口，再投递向量化阶段。
     * 快照不在切片阶段（已处理、已删除或被重新处理）时返回 false，消息直接确认。
     */
    @Transactional
    public boolean chunkStage(String workspaceId, String sourceId, String snapshotId, String textObjectKey,
                              String mimeType, int pageCount) {
        SourceMeta meta = loadSourceMetaForProcessing(workspaceId, sourceId, snapshotId);
        String stage = jdbcTemplate.queryForObject(
                "select processing_stage from source_snapshot where id = ?", String.class, snapshotId);
        if (!meta.processable() || !SourcePipelineStages.STAGE_CHUNKING.equals(stage)) {
            log.info("Skip source chunk stage: sourceId={}, snapshotId={}, stage={}", sourceId, snapshotId, stage);
            return false;
        }
        String text = new String(storage.read(SourcePipelineStages.BUCKET_DERIVED, textObjectKey),
                java.nio.charset.StandardCharsets.UTF_8);
        writeChunksAndQueueEmbedding(workspaceId, sourceId, snapshotId, meta,
                new SourceDocumentTextExtractor.ExtractedDocument(text, mimeType, pageCount),
                findSourceParseTaskId(sourceId));
        return true;
    }

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
                       ss.index_status as snapshot_index_status, ss.processing_stage
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
                select s.title, s.source_type, s.status, ss.parse_status,
                       coalesce(fo.mime_type, 'text/markdown') as mime_type
                from source s
                join source_snapshot ss on ss.id = ? and ss.source_id = s.id
                left join file_object fo on fo.id = s.file_object_id
                where s.id = ? and s.workspace_id = ?
                """ + (lock ? " for update" : ""), rs -> {
            if (!rs.next()) {
                throw new BusinessException("SOURCE_NOT_FOUND", "资料不存在");
            }
            boolean processable = !"DELETED".equals(rs.getString("status"))
                    && "PENDING".equals(rs.getString("parse_status"));
            return new SourceMeta(
                    rs.getString("title"),
                    rs.getString("source_type"),
                    rs.getString("mime_type"),
                    processable
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

    private String summarize(String text) {
        String normalized = text == null ? "" : text.replace("\r", "").replace("\n", " ").trim();
        if (normalized.length() <= 360) {
            return normalized;
        }
        return normalized.substring(0, 359) + "...";
    }

    private List<String> deriveTags(String title, String sourceType, String text) {
        Set<String> tags = new LinkedHashSet<>();
        if (sourceType != null && !sourceType.isBlank()) {
            tags.add(sourceType.toLowerCase(Locale.ROOT));
        }
        collectTerms(tags, title);
        collectTerms(tags, text == null ? "" : text.substring(0, Math.min(text.length(), 800)));
        return new ArrayList<>(tags).stream().limit(12).toList();
    }

    private void collectTerms(Set<String> tags, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String[] parts = value.toLowerCase(Locale.ROOT).split("[^\\p{IsHan}a-zA-Z0-9]+");
        for (String part : parts) {
            if (part.length() >= 2) {
                tags.add(part);
            }
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("JSON_WRITE_FAILED", "资料元数据序列化失败");
        }
    }

    private List<String> buildReadWindows(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank()) {
            return List.of("");
        }
        if (normalized.length() <= WINDOW_CHARS) {
            return List.of(normalized);
        }
        List<String> windows = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + WINDOW_CHARS);
            if (end < normalized.length()) {
                int paragraphBreak = normalized.lastIndexOf("\n\n", end);
                if (paragraphBreak > start + WINDOW_CHARS / 2) {
                    end = paragraphBreak;
                }
            }
            windows.add(normalized.substring(start, end).trim());
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - WINDOW_OVERLAP, start + 1);
        }
        return windows;
    }

    private record SourceMeta(String title, String sourceType, String mimeType, boolean processable) {
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
