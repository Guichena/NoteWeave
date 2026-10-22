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
import com.noteweave.source.SourceTopicNames;
import com.noteweave.source.SourceTopicNamesFixture;
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
                new ObjectMapper(), projectionFinalizer, SourceTopicNamesFixture.defaults())
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

    /** 主题名改成配置里的自定义值之后，识别和收尾都必须跟着走，不能只认缺省常量。 */
    @Test
    void customTopicNamesShouldStillBeHandledAndFinalized() {
        SourceTopicNames custom = SourceTopicNamesFixture.custom(
                "acme.parse", "acme.chunk", "acme.embed", "acme.index", "acme.projection");
        RetrievalProjectionDeadLetterFinalizer customFinalizer = new RetrievalProjectionDeadLetterFinalizer(
                new ObjectMapper(), projectionFinalizer, custom).withEmbeddingBatches(embeddingBatches);
        when(embeddingBatches.acceptsFailure(
                "workspace-1", "source-1", "snapshot-1", "task-1", 0, VERSION)).thenReturn(true);

        assertThat(customFinalizer.handles("acme.embed")).isTrue();
        assertThat(customFinalizer.handles("acme.index")).isTrue();
        assertThat(customFinalizer.handles("acme.projection")).isTrue();
        assertThat(customFinalizer.handles(SourcePipelineStages.TOPIC_EMBED)).isFalse();
        assertThat(customFinalizer.handles(SourcePipelineStages.TOPIC_INDEX)).isFalse();

        boolean finalized = customFinalizer.finalizeFailure("acme.embed", EMBED_PAYLOAD,
                RetrievalProjectionDeadLetterFinalizer.DELIVERY_EXHAUSTED_ERROR_CODE);

        assertThat(finalized).isTrue();
        verify(projectionFinalizer).finalizeFailed("workspace-1", "source-1", "snapshot-1", "task-1",
                "OUTBOX_DISPATCH_EXHAUSTED");
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
