package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.source.SourcePipelineStages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 检索投影消息无法完成时的公共收尾逻辑，与 Spring 上下文无关。 */
class RetrievalProjectionDeadLetterFinalizerTest {

    private static final String VERSION = "a".repeat(64);
    private static final String EMBED_PAYLOAD = """
            {"workspaceId":"workspace-1","sourceId":"source-1","sourceSnapshotId":"snapshot-1",
             "taskId":"task-1","batchNo":0,"batchVersion":"%s"}
            """.formatted(VERSION);
    private static final String INDEX_PAYLOAD = """
            {"workspaceId":"workspace-1","sourceId":"source-1","sourceSnapshotId":"snapshot-1",
             "taskId":"task-1","batchVersion":"%s"}
            """.formatted(VERSION);

    private SourceRetrievalProjectionFinalizer projectionFinalizer;
    private SourceEmbeddingBatchRepository embeddingBatches;
    private RetrievalProjectionDeadLetterFinalizer finalizer;

    @BeforeEach
    void setUp() {
        projectionFinalizer = mock(SourceRetrievalProjectionFinalizer.class);
        embeddingBatches = mock(SourceEmbeddingBatchRepository.class);
        finalizer = new RetrievalProjectionDeadLetterFinalizer(
                new ObjectMapper(), projectionFinalizer, "noteweave.retrieval.projection")
                .withEmbeddingBatches(embeddingBatches);
    }

    @Test
    void shouldHandleEveryRetrievalProjectionTopic() {
        assertThat(finalizer.handles(SourcePipelineStages.TOPIC_EMBED)).isTrue();
        assertThat(finalizer.handles(SourcePipelineStages.TOPIC_INDEX)).isTrue();
        assertThat(finalizer.handles("noteweave.retrieval.projection")).isTrue();
        assertThat(finalizer.handles(SourcePipelineStages.TOPIC_PARSE)).isFalse();
        assertThat(finalizer.handles(SourcePipelineStages.TOPIC_CHUNK)).isFalse();
        assertThat(finalizer.handles(null)).isFalse();
    }

    @Test
    void embedExhaustionShouldFinalizeSnapshotAndTaskFailure() {
        when(embeddingBatches.acceptsFailure(
                "workspace-1", "source-1", "snapshot-1", "task-1", 0, VERSION)).thenReturn(true);

        boolean finalized = finalizer.finalizeFailure(SourcePipelineStages.TOPIC_EMBED, EMBED_PAYLOAD,
                RetrievalProjectionDeadLetterFinalizer.DELIVERY_EXHAUSTED_ERROR_CODE);

        assertThat(finalized).isTrue();
        verify(projectionFinalizer).finalizeFailed("workspace-1", "source-1", "snapshot-1", "task-1",
                "OUTBOX_DISPATCH_EXHAUSTED");
    }

    @Test
    void indexExhaustionShouldFinalizeSnapshotAndTaskFailure() {
        when(embeddingBatches.current(
                "workspace-1", "source-1", "snapshot-1", "task-1", "INDEXING")).thenReturn(true);

        boolean finalized = finalizer.finalizeFailure(SourcePipelineStages.TOPIC_INDEX, INDEX_PAYLOAD,
                RetrievalProjectionDeadLetterFinalizer.DELIVERY_EXHAUSTED_ERROR_CODE);

        assertThat(finalized).isTrue();
        verify(projectionFinalizer).finalizeFailed("workspace-1", "source-1", "snapshot-1", "task-1",
                "OUTBOX_DISPATCH_EXHAUSTED");
    }

    @Test
    void batchWithLiveLeaseShouldNotBeFinalized() {
        when(embeddingBatches.acceptsFailure(
                "workspace-1", "source-1", "snapshot-1", "task-1", 0, VERSION)).thenReturn(false);

        boolean finalized = finalizer.finalizeFailure(SourcePipelineStages.TOPIC_EMBED, EMBED_PAYLOAD,
                RetrievalProjectionDeadLetterFinalizer.DELIVERY_EXHAUSTED_ERROR_CODE);

        assertThat(finalized).isFalse();
        verify(projectionFinalizer, never()).finalizeFailed(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void failedFinalizationShouldOnlyBeLogged() {
        when(embeddingBatches.acceptsFailure(
                "workspace-1", "source-1", "snapshot-1", "task-1", 0, VERSION)).thenReturn(true);
        doThrow(new IllegalStateException("task already locked")).when(projectionFinalizer)
                .finalizeFailed("workspace-1", "source-1", "snapshot-1", "task-1", "OUTBOX_DISPATCH_EXHAUSTED");

        boolean finalized = finalizer.finalizeFailure(SourcePipelineStages.TOPIC_EMBED, EMBED_PAYLOAD,
                RetrievalProjectionDeadLetterFinalizer.DELIVERY_EXHAUSTED_ERROR_CODE);

        assertThat(finalized).isFalse();
    }

    @Test
    void unusablePayloadShouldBeIgnored() {
        assertThat(finalizer.finalizeFailure(SourcePipelineStages.TOPIC_EMBED, "not-json", "OUTBOX_DISPATCH_EXHAUSTED"))
                .isFalse();
        assertThat(finalizer.finalizeFailure(SourcePipelineStages.TOPIC_INDEX,
                """
                {"workspaceId":"workspace-1","taskId":"task-1"}
                """, "OUTBOX_DISPATCH_EXHAUSTED")).isFalse();
        verify(projectionFinalizer, never()).finalizeFailed(anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
