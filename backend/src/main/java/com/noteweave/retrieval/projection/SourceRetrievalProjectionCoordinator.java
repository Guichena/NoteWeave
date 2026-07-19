package com.noteweave.retrieval.projection;

import com.noteweave.retrieval.index.RetrievalIndexManager;
import com.noteweave.retrieval.index.RetrievalIndexNames;
import com.noteweave.retrieval.index.RetrievalProjectionWriter;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionFinalizer.StaleSnapshot;
import org.springframework.stereotype.Service;

@Service
public class SourceRetrievalProjectionCoordinator {
    private final SourceRetrievalProjectionService projectionService;
    private final SourceRetrievalProjectionFinalizer finalizer;
    private final RetrievalProjectionWriter projectionWriter;
    private final RetrievalIndexManager indexManager;

    public SourceRetrievalProjectionCoordinator(
            SourceRetrievalProjectionService projectionService,
            SourceRetrievalProjectionFinalizer finalizer,
            RetrievalProjectionWriter projectionWriter,
            RetrievalIndexManager indexManager
    ) {
        this.projectionService = projectionService;
        this.finalizer = finalizer;
        this.projectionWriter = projectionWriter;
        this.indexManager = indexManager;
    }

    public SourceRetrievalProjectionService.ProjectionResult projectAndFinalize(
            String workspaceId, String sourceId, String snapshotId, String taskId
    ) {
        SourceRetrievalProjectionService.ProjectionResult result =
                projectionService.projectSnapshot(workspaceId, sourceId, snapshotId);
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
            finalizer.finalizeReady(workspaceId, sourceId, snapshotId, taskId, result);
            finalized = true;
            for (StaleSnapshot stale : finalizer.staleSnapshots(workspaceId, sourceId, snapshotId)) {
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
