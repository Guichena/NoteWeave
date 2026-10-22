package com.noteweave.retrieval.projection;

import com.noteweave.retrieval.index.RetrievalIndexManager;
import com.noteweave.retrieval.index.RetrievalIndexNames;
import com.noteweave.retrieval.index.RetrievalProjectionWriter;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionFinalizer.StaleSnapshot;
import com.noteweave.source.SourcePipelineStages;
import com.noteweave.task.TaskCommandPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class SourceRetrievalProjectionCoordinator {
    private final SourceRetrievalProjectionService projectionService;
    private final SourceRetrievalProjectionFinalizer finalizer;
    private final RetrievalProjectionWriter projectionWriter;
    private final RetrievalIndexManager indexManager;
    private final JdbcTemplate jdbcTemplate;
    private final TaskCommandPort taskCommandPort;
    private final SnapshotEmbeddingStore embeddingStore;
    private final SourceEmbeddingBatchRepository batchRepository;
    private com.noteweave.source.SourceCatalogVersionService catalogVersionService;

    private static final Logger log = LoggerFactory.getLogger(SourceRetrievalProjectionCoordinator.class);

    public SourceRetrievalProjectionCoordinator(
            SourceRetrievalProjectionService projectionService,
            SourceRetrievalProjectionFinalizer finalizer,
            RetrievalProjectionWriter projectionWriter,
            RetrievalIndexManager indexManager
    ) {
        this(projectionService, finalizer, projectionWriter, indexManager, null, null, null, null,
                (com.noteweave.source.SourceTopicNames) null);
    }

    public SourceRetrievalProjectionCoordinator(
            SourceRetrievalProjectionService projectionService,
            SourceRetrievalProjectionFinalizer finalizer,
            RetrievalProjectionWriter projectionWriter,
            RetrievalIndexManager indexManager,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            TaskCommandPort taskCommandPort,
            SnapshotEmbeddingStore embeddingStore,
            com.noteweave.source.SourceTopicNames topicNames
    ) {
        this(projectionService, finalizer, projectionWriter, indexManager, jdbcTemplate, transactionManager,
                taskCommandPort, embeddingStore, jdbcTemplate == null || transactionManager == null ? null
                        : new SourceEmbeddingBatchRepository(jdbcTemplate, transactionManager, topicNames));
    }

    @Autowired
    public SourceRetrievalProjectionCoordinator(SourceRetrievalProjectionService projectionService,
            SourceRetrievalProjectionFinalizer finalizer, RetrievalProjectionWriter projectionWriter,
            RetrievalIndexManager indexManager, JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
            TaskCommandPort taskCommandPort, SnapshotEmbeddingStore embeddingStore,
            SourceEmbeddingBatchRepository batchRepository) {
        this.projectionService = projectionService;
        this.finalizer = finalizer;
        this.projectionWriter = projectionWriter;
        this.indexManager = indexManager;
        this.jdbcTemplate = jdbcTemplate;
        this.taskCommandPort = taskCommandPort;
        this.embeddingStore = embeddingStore;
        this.batchRepository = batchRepository;
    }

    /**
     * 向量化阶段：为片段和资料计算向量，写入派生存储后投递索引阶段。
     * 快照不在向量化阶段时返回 false（重复消息或已被重新处理），消息直接确认。
     */
    public boolean embedStage(String workspaceId, String sourceId, String snapshotId, String taskId) {
        if (!batchRepository.current(workspaceId, sourceId, snapshotId, taskId, SourcePipelineStages.STAGE_EMBEDDING)) return false;
        var rows = batchRepository.initialize(workspaceId, sourceId, snapshotId, taskId,
                projectionService.currentBatchEmbeddingVersion(), projectionService.currentChunkerVersion(workspaceId, sourceId, snapshotId),
                projectionService.currentContextPromptVersion(), projectionService::currentEmbeddingRequestBudget);
        if (rows.isEmpty()) return false;
        boolean queued = batchRepository.queueIndexIfComplete(workspaceId, sourceId, snapshotId, taskId,
                rows.get(0).batchVersion(), embeddingStore::saveManifest);
        if (queued) recordIndexing(taskId, workspaceId, rows.get(0).totalChunks());
        return true;
    }

    /** 批次消息只计算至多 64 个片段，完成结果与上下文账本可独立恢复。 */
    public boolean embedStage(String workspaceId, String sourceId, String snapshotId, String taskId,
            Integer batchNo, String batchVersion) {
        if (batchNo == null || batchNo < 0 || batchVersion == null || !batchVersion.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("无效的向量批次消息");
        var claimed = batchRepository.claim(workspaceId, sourceId, snapshotId, taskId, batchNo, batchVersion);
        if (claimed == null) return false;
        try {
            if (!claimed.embeddingVersion().equals(projectionService.currentBatchEmbeddingVersion())
                    || !claimed.chunkerVersion().equals(projectionService.currentChunkerVersion(workspaceId, sourceId, snapshotId))
                    || !claimed.contextPromptVersion().equals(projectionService.currentContextPromptVersion()))
                throw new IllegalStateException("冻结批次配置已变化");
            var embeddings = projectionService.computeEmbeddingBatch(workspaceId, sourceId, snapshotId, batchNo,
                    increment -> batchRepository.beforeModelCall(claimed, increment));
            var stored = embeddingStore.saveBatch(claimed, embeddings);
            boolean queued = batchRepository.complete(claimed, stored.objectKey(), stored.sha256(), embeddingStore::saveManifest);
            if (queued) recordIndexing(taskId, workspaceId, claimed.totalChunks());
            return true;
        } catch (RuntimeException failure) {
            batchRepository.release(claimed);
            throw failure;
        }
    }

    private void recordIndexing(String taskId, String workspaceId, int chunks) {
        if (taskId != null && !taskId.isBlank()) taskCommandPort.recordStage(taskId, SourcePipelineStages.STAGE_INDEXING,
                "向量化完成，共 " + chunks + " 个片段向量，等待写入检索索引");
        if (catalogVersionService != null) catalogVersionService.bump(workspaceId);
    }

    /**
     * 索引阶段：读取向量化阶段的结果写入检索索引并完成收尾。
     * 新批次清单严格验证后逐批读取；旧 NWE1 文件保持历史重算兼容。
     */
    public boolean indexStage(String workspaceId, String sourceId, String snapshotId, String taskId,
                              String embeddingsObjectKey) {
        if (!batchRepository.current(workspaceId, sourceId, snapshotId, taskId, SourcePipelineStages.STAGE_INDEXING)) {
            log.info("Skip source index stage: sourceId={}, snapshotId={}", sourceId, snapshotId);
            return false;
        }
        Integer checkpointCount = jdbcTemplate.queryForObject("""
                select count(*) from source_embedding_batch where workspace_id = ? and source_id = ?
                    and source_snapshot_id = ? and task_id = ?
                """, Integer.class, workspaceId, sourceId, snapshotId, taskId);
        if (checkpointCount != null && checkpointCount > 0 && !SnapshotEmbeddingStore.isManifest(embeddingsObjectKey))
            throw new IllegalStateException("SOURCE_EMBEDDING_MANIFEST_INVALID: 批次索引消息没有合法清单");
        if (SnapshotEmbeddingStore.isManifest(embeddingsObjectKey)) {
            var manifest = embeddingStore.loadManifest(embeddingsObjectKey, workspaceId, sourceId, snapshotId);
            if (!batchRepository.activeVersion(snapshotId, manifest.batchVersion())) return false;
            var rows = batchRepository.list(workspaceId, sourceId, snapshotId, manifest.batchVersion());
            if (rows.size() != manifest.batches().size()) throw new IllegalStateException("批次清单缺少持久化检查点");
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i); var entry = manifest.batches().get(i);
                if (!taskId.equals(row.taskId()) || !"COMPLETED".equals(row.status())
                        || !entry.objectKey().equals(row.objectKey()) || !entry.sha256().equals(row.sha256()))
                    throw new IllegalStateException("批次清单与持久化检查点不符");
            }
            Iterable<SourceRetrievalProjectionService.BatchEmbeddings> batches = () -> manifest.batches().stream()
                    .map(entry -> {
                        if (!batchRepository.current(workspaceId, sourceId, snapshotId, taskId, SourcePipelineStages.STAGE_INDEXING))
                            throw new IllegalStateException("索引任务已失效");
                        return embeddingStore.loadBatch(manifest, entry);
                    }).iterator();
            var result = projectionService.projectSnapshotBatches(workspaceId, sourceId, snapshotId, batches);
            if (!batchRepository.current(workspaceId, sourceId, snapshotId, taskId, SourcePipelineStages.STAGE_INDEXING)) {
                IllegalStateException stale = new IllegalStateException("索引提交任务已失效");
                deactivateSnapshot(result, snapshotId, stale);
                throw stale;
            }
            finalizeProjection(workspaceId, sourceId, snapshotId, taskId, result);
            return true;
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
