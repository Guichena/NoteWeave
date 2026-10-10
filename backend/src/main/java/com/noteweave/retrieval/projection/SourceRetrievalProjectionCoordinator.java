package com.noteweave.retrieval.projection;

import com.noteweave.retrieval.index.RetrievalIndexManager;
import com.noteweave.retrieval.index.RetrievalIndexNames;
import com.noteweave.retrieval.index.RetrievalProjectionWriter;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionFinalizer.StaleSnapshot;
import com.noteweave.common.Ids;
import com.noteweave.source.SourcePipelineStages;
import com.noteweave.task.TaskCommandPort;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SourceRetrievalProjectionCoordinator {
    private final SourceRetrievalProjectionService projectionService;
    private final SourceRetrievalProjectionFinalizer finalizer;
    private final RetrievalProjectionWriter projectionWriter;
    private final RetrievalIndexManager indexManager;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final TaskCommandPort taskCommandPort;
    private final SnapshotEmbeddingStore embeddingStore;
    private com.noteweave.source.SourceCatalogVersionService catalogVersionService;

    private static final Logger log = LoggerFactory.getLogger(SourceRetrievalProjectionCoordinator.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper PAYLOAD_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public SourceRetrievalProjectionCoordinator(
            SourceRetrievalProjectionService projectionService,
            SourceRetrievalProjectionFinalizer finalizer,
            RetrievalProjectionWriter projectionWriter,
            RetrievalIndexManager indexManager
    ) {
        this(projectionService, finalizer, projectionWriter, indexManager, null, null, null, null);
    }

    @Autowired
    public SourceRetrievalProjectionCoordinator(
            SourceRetrievalProjectionService projectionService,
            SourceRetrievalProjectionFinalizer finalizer,
            RetrievalProjectionWriter projectionWriter,
            RetrievalIndexManager indexManager,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            TaskCommandPort taskCommandPort,
            SnapshotEmbeddingStore embeddingStore
    ) {
        this.projectionService = projectionService;
        this.finalizer = finalizer;
        this.projectionWriter = projectionWriter;
        this.indexManager = indexManager;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.taskCommandPort = taskCommandPort;
        this.embeddingStore = embeddingStore;
    }

    /**
     * 向量化阶段：为片段和资料计算向量，写入派生存储后投递索引阶段。
     * 快照不在向量化阶段时返回 false（重复消息或已被重新处理），消息直接确认。
     */
    public boolean embedStage(String workspaceId, String sourceId, String snapshotId, String taskId) {
        if (!SourcePipelineStages.STAGE_EMBEDDING.equals(stage(snapshotId))) {
            log.info("Skip source embed stage: sourceId={}, snapshotId={}", sourceId, snapshotId);
            return false;
        }
        SourceRetrievalProjectionService.SnapshotEmbeddings embeddings =
                projectionService.computeEmbeddings(workspaceId, sourceId, snapshotId);
        String objectKey = embeddingStore.save(workspaceId, sourceId, snapshotId, embeddings);
        Boolean queued = transactionTemplate.execute(status -> {
            int moved = jdbcTemplate.update("""
                    update source_snapshot set processing_stage = ?
                    where id = ? and source_id = ? and processing_stage = ?
                      and parse_status = 'PARSED' and index_status = 'INDEXING'
                    """, SourcePipelineStages.STAGE_INDEXING, snapshotId, sourceId,
                    SourcePipelineStages.STAGE_EMBEDDING);
            if (moved != 1) {
                return false;
            }
            if (taskId != null && !taskId.isBlank()) {
                taskCommandPort.recordStage(taskId, SourcePipelineStages.STAGE_INDEXING,
                        "向量化完成，共 " + embeddings.chunkIds().size() + " 个片段向量，等待写入检索索引");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            if (taskId != null) payload.put("taskId", taskId);
            payload.put("workspaceId", workspaceId);
            payload.put("sourceId", sourceId);
            payload.put("sourceSnapshotId", snapshotId);
            payload.put("embeddingsObjectKey", objectKey);
            jdbcTemplate.update("""
                    insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                    values (?, ?, ?, ?, ?, 'READY')
                    """, Ids.newId(), taskId, SourcePipelineStages.TOPIC_INDEX, snapshotId,
                    com.noteweave.common.Json.write(PAYLOAD_MAPPER, payload));
            if (catalogVersionService != null) catalogVersionService.bump(workspaceId);
            return true;
        });
        return Boolean.TRUE.equals(queued);
    }

    /**
     * 索引阶段：读取向量化阶段的结果写入检索索引并完成收尾。
     * 派生存储里的向量缺失或与当前片段不一致时重新计算，保证结果正确。
     */
    public boolean indexStage(String workspaceId, String sourceId, String snapshotId, String taskId,
                              String embeddingsObjectKey) {
        if (!SourcePipelineStages.STAGE_INDEXING.equals(stage(snapshotId))) {
            log.info("Skip source index stage: sourceId={}, snapshotId={}", sourceId, snapshotId);
            return false;
        }
        SourceRetrievalProjectionService.SnapshotEmbeddings embeddings = embeddingStore.load(embeddingsObjectKey);
        if (embeddings != null && !embeddings.embeddingVersion().equals(projectionService.currentEmbeddingVersion())) {
            embeddings = null;
        }
        SourceRetrievalProjectionService.ProjectionResult result;
        try {
            result = projectionService.projectSnapshot(workspaceId, sourceId, snapshotId, embeddings);
        } catch (IllegalStateException stale) {
            if (embeddings == null) throw stale;
            log.info("Stored embeddings are stale, recomputing: snapshotId={}", snapshotId);
            result = projectionService.projectSnapshot(workspaceId, sourceId, snapshotId, null);
        }
        finalizeProjection(workspaceId, sourceId, snapshotId, taskId, result);
        return true;
    }

    /** 阶段变化时刷新资料列表缓存，前端轮询时能看到当前阶段。 */
    @Autowired(required = false)
    void setCatalogVersionService(com.noteweave.source.SourceCatalogVersionService catalogVersionService) {
        this.catalogVersionService = catalogVersionService;
    }

    private String stage(String snapshotId) {
        return jdbcTemplate.query("select processing_stage from source_snapshot where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, snapshotId);
    }

    public SourceRetrievalProjectionService.ProjectionResult projectAndFinalize(
            String workspaceId, String sourceId, String snapshotId, String taskId
    ) {
        SourceRetrievalProjectionService.ProjectionResult result =
                projectionService.projectSnapshot(workspaceId, sourceId, snapshotId);
        return finalizeProjection(workspaceId, sourceId, snapshotId, taskId, result);
    }

    private SourceRetrievalProjectionService.ProjectionResult finalizeProjection(
            String workspaceId, String sourceId, String snapshotId, String taskId,
            SourceRetrievalProjectionService.ProjectionResult result
    ) {
        if (!result.complete()) {
            throw new IllegalStateException("Retrieval projection did not satisfy the dual READY gate");
        }
        boolean finalized = false;
        try {
            indexManager.ensureAlias(
                    RetrievalIndexNames.alias(RetrievalProjectionRepository.ProjectionType.QA_CHUNK, workspaceId),
                    result.qaIndex());
            indexManager.ensureAlias(
                    RetrievalIndexNames.alias(RetrievalProjectionRepository.ProjectionType.NOTE_SOURCE, workspaceId),
                    result.noteIndex());
            projectionWriter.markSnapshotCurrent(result.qaIndex(), snapshotId);
            projectionWriter.markSnapshotCurrent(result.noteIndex(), snapshotId);
            java.util.List<StaleSnapshot> staleSnapshots = finalizer.finalizeReady(
                    workspaceId, sourceId, snapshotId, taskId, result);
            finalized = true;
            for (StaleSnapshot stale : staleSnapshots) {
                projectionWriter.markSnapshotNotCurrent(stale.targetIndex(), stale.sourceSnapshotId());
            }
            return result;
        } catch (RuntimeException ex) {
            if (!finalized) {
                deactivateSnapshot(result, snapshotId, ex);
            }
            throw ex;
        }
    }

    private void deactivateSnapshot(
            SourceRetrievalProjectionService.ProjectionResult result,
            String snapshotId,
            RuntimeException original
    ) {
        for (String index : java.util.List.of(result.qaIndex(), result.noteIndex())) {
            try {
                projectionWriter.markSnapshotNotCurrent(index, snapshotId);
            } catch (RuntimeException cleanupFailure) {
                original.addSuppressed(cleanupFailure);
            }
        }
    }
}
