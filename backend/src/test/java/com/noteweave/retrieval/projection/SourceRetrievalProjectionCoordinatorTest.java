package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.retrieval.index.RetrievalIndexManager;
import com.noteweave.retrieval.index.RetrievalProjectionWriter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SourceRetrievalProjectionCoordinatorTest {

    private final SourceRetrievalProjectionService projectionService =
            mock(SourceRetrievalProjectionService.class);
    private final SourceRetrievalProjectionFinalizer finalizer =
            mock(SourceRetrievalProjectionFinalizer.class);
    private final RetrievalProjectionWriter projectionWriter = mock(RetrievalProjectionWriter.class);
    private final RetrievalIndexManager indexManager = mock(RetrievalIndexManager.class);
    private final SourceRetrievalProjectionCoordinator coordinator =
            new SourceRetrievalProjectionCoordinator(
                    projectionService, finalizer, projectionWriter, indexManager);

    @Test
    void finalizationFailureShouldDeactivateBothNewProjectionDocuments() {
        SourceRetrievalProjectionService.ProjectionResult result = completeResult();
        when(projectionService.projectSnapshot("workspace-1", "source-1", "snapshot-1"))
                .thenReturn(result);
        doThrow(new IllegalStateException("snapshot is no longer current"))
                .when(finalizer)
                .finalizeReady("workspace-1", "source-1", "snapshot-1", "task-1", result);

        assertThatThrownBy(() -> coordinator.projectAndFinalize(
                "workspace-1", "source-1", "snapshot-1", "task-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer current");

        verify(projectionWriter).markSnapshotNotCurrent("qa-index", "snapshot-1");
        verify(projectionWriter).markSnapshotNotCurrent("note-index", "snapshot-1");
    }

    @Test
    void staleSnapshotsShouldOnlyBeDeactivatedAfterNewSnapshotFinalizes() {
        SourceRetrievalProjectionService.ProjectionResult result = completeResult();
        SourceRetrievalProjectionFinalizer.StaleSnapshot stale =
                new SourceRetrievalProjectionFinalizer.StaleSnapshot(
                        "snapshot-0",
                        RetrievalProjectionRepository.ProjectionType.QA_CHUNK,
                        "qa-index");
        when(projectionService.projectSnapshot("workspace-1", "source-1", "snapshot-1"))
                .thenReturn(result);
        when(finalizer.staleSnapshots("workspace-1", "source-1", "snapshot-1"))
                .thenReturn(List.of(stale));

        coordinator.projectAndFinalize("workspace-1", "source-1", "snapshot-1", "task-1");

        InOrder order = inOrder(finalizer, projectionWriter);
        order.verify(finalizer).finalizeReady(
                "workspace-1", "source-1", "snapshot-1", "task-1", result);
        order.verify(projectionWriter).markSnapshotNotCurrent("qa-index", "snapshot-0");
    }

    private SourceRetrievalProjectionService.ProjectionResult completeResult() {
        return new SourceRetrievalProjectionService.ProjectionResult(
                2, 2, true, "qa-index", "note-index", "embedding-v1");
    }
}
