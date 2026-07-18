package com.noteweave.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskCommandPort;
import com.noteweave.security.AuditActorProvider;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final ObjectMapper objectMapper;
    private final ObjectStorage storage;
    private final SourceMessagingMode messagingMode;
    private final TaskCommandPort taskCommandPort;
    private final TransactionTemplate transactionTemplate;
    private final AuditActorProvider auditActorProvider;
    private final SourceCatalogVersionService sourceCatalogVersionService;

    public SourceParseService(JdbcTemplate jdbcTemplate, DocumentChunker documentChunker, ObjectMapper objectMapper,
                              ObjectStorage storage, SourceMessagingMode messagingMode, TaskCommandPort taskCommandPort,
                              PlatformTransactionManager transactionManager, AuditActorProvider auditActorProvider,
                              SourceCatalogVersionService sourceCatalogVersionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentChunker = documentChunker;
        this.objectMapper = objectMapper;
        this.storage = storage;
        this.messagingMode = messagingMode;
        this.taskCommandPort = taskCommandPort;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.auditActorProvider = auditActorProvider;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
    }

    @Transactional
    public void parseAndIndex(String workspaceId, String sourceId, String snapshotId, byte[] bytes) {
        SourceMeta sourceMeta = loadSourceMetaForProcessing(workspaceId, sourceId, snapshotId);
        if (!sourceMeta.processable()) {
            log.info("Skip source parse because source/snapshot is no longer processable: sourceId={}, snapshotId={}",
                    sourceId, snapshotId);
            return;
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        String actor = auditActorProvider.currentOrSystem("SOURCE_PARSE");
        String sourceParseTaskId = findSourceParseTaskId(sourceId);
        if (messagingMode.isAsyncEnabled() && sourceParseTaskId == null) {
            sourceParseTaskId = taskCommandPort.createTask(
                    workspaceId, "SOURCE_PARSE", "SOURCE", sourceId, "PARSING", "生成资料解析与索引");
            taskCommandPort.startTask(sourceParseTaskId);
        }
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
            Map<String, Object> indexPayload = new java.util.LinkedHashMap<>();
            if (sourceParseTaskId != null) {
                indexPayload.put("taskId", sourceParseTaskId);
            }
            indexPayload.put("workspaceId", workspaceId);
            indexPayload.put("sourceId", sourceId);
            indexPayload.put("sourceSnapshotId", snapshotId);
            indexPayload.put("chunkId", chunkId);
            indexPayload.put("chunkNo", i);
            indexPayload.put("title", sourceMeta.title());
            indexPayload.put("sourceType", sourceMeta.sourceType());
            indexPayload.put("content", content);
            if (messagingMode.isAsyncEnabled()) {
                jdbcTemplate.update("""
                        insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                        values (?, ?, 'noteweave.source.index', ?, ?, 'READY')
                        """, Ids.newId(), sourceParseTaskId, chunkId, writeJson(indexPayload));
            } else {
                jdbcTemplate.update("""
                        update source_chunk set projection_status = 'PROJECTED', projected_at = current_timestamp where id = ?
                        """, chunkId);
            }
        }

        List<String> tags = deriveTags(sourceMeta.title(), sourceMeta.sourceType(), text);
        Map<String, Object> metadata = Map.of(
                "title", sourceMeta.title(),
                "source_type", sourceMeta.sourceType(),
                "chunk_count", chunks.size(),
                "char_count", text.length(),
                "entry_strategy", "marginalia_structured_reading_funnel"
        );
        jdbcTemplate.update("""
                update source
                set summary = ?, tags_json = ?, metadata_json = ?,
                    parse_status = 'PARSED', index_status = ?, status = ?,
                    updated_by = ?, updated_at = current_timestamp
                where id = ? and status <> 'DELETED'
                """, summarize(text), writeJson(tags), writeJson(metadata),
                messagingMode.isAsyncEnabled() ? "INDEXING" : "INDEXED",
                messagingMode.isAsyncEnabled() ? "PROCESSING" : "READY", actor, sourceId);
        jdbcTemplate.update("update source_snapshot set parse_status = 'PARSED', index_status = ? where id = ?",
                messagingMode.isAsyncEnabled() ? "INDEXING" : "INDEXED", snapshotId);
        sourceCatalogVersionService.bump(workspaceId);
    }

    /**
     * 异步入口：从 MinIO 重新读取原始文件，再调用同步版 parseAndIndex。
     * 由 KafkaTaskConsumer.onSourceParse 调用。
     */
    public void parseAndIndexAsync(String workspaceId, String sourceId, String snapshotId) {
        try {
            if (!isProcessable(workspaceId, sourceId, snapshotId)) {
                log.info("Skip async source parse because source/snapshot is no longer processable: sourceId={}, snapshotId={}",
                        sourceId, snapshotId);
                return;
            }
            String objectKey = jdbcTemplate.queryForObject("""
                    select object_key from source_snapshot where id = ?
                    """, String.class, snapshotId);
            byte[] bytes = storage.read(BUCKET_SOURCE, objectKey);
            transactionTemplate.executeWithoutResult(
                    ignored -> parseAndIndex(workspaceId, sourceId, snapshotId, bytes)
            );
            log.info("SourceParseService async parse OK: sourceId={}, snapshotId={}", sourceId, snapshotId);
        } catch (Exception ex) {
            log.error("SourceParseService async parse failed for sourceId={}: {}", sourceId, ex.getMessage(), ex);
            throw new IllegalStateException("async source parse failed for sourceId=" + sourceId, ex);
        }
    }

    public boolean isProcessable(String workspaceId, String sourceId, String snapshotId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from source s
                join source_snapshot ss on ss.id = ? and ss.source_id = s.id
                where s.id = ? and s.workspace_id = ?
                  and s.status <> 'DELETED'
                  and ss.parse_status = 'PENDING'
                """, Integer.class, snapshotId, sourceId, workspaceId);
        return count != null && count == 1;
    }

    private SourceMeta loadSourceMetaForProcessing(String workspaceId, String sourceId, String snapshotId) {
        return jdbcTemplate.query("""
                select s.title, s.source_type, s.status, ss.parse_status
                from source s
                join source_snapshot ss on ss.id = ? and ss.source_id = s.id
                where s.id = ? and s.workspace_id = ?
                for update
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("SOURCE_NOT_FOUND", "资料不存在");
            }
            boolean processable = !"DELETED".equals(rs.getString("status"))
                    && "PENDING".equals(rs.getString("parse_status"));
            return new SourceMeta(rs.getString("title"), rs.getString("source_type"), processable);
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

    private record SourceMeta(String title, String sourceType, boolean processable) {
    }
}
