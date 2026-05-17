package com.noteweave.team.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.task.model.Task;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.worker.TaskExecutionContext;
import com.noteweave.task.worker.TaskResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmbeddingBackfillTaskWorkerTest {

    @Mock
    private VectorIndexerService vectorIndexerService;

    @InjectMocks
    private EmbeddingBackfillTaskWorker worker;

    @Test
    void shouldBackfillRequestedDocumentAndReturnDocumentReference() {
        Task task = new Task();
        task.setId(91L);
        task.setUserId(7L);
        task.setSpaceId(5L);
        task.setTaskType(TaskType.EMBEDDING_BACKFILL);
        task.setInputJson("""
                {"documentId":321,"knowledgeBaseId":55}
                """);

        when(vectorIndexerService.backfillDocumentEmbeddings(321L, 55L)).thenReturn(4);

        TaskResult result = worker.execute(new TaskExecutionContext(task, new ObjectMapper(), null, null));

        assertThat(worker.taskType()).isEqualTo(TaskType.EMBEDDING_BACKFILL);
        assertThat(result.getResultRefType()).isEqualTo("DOCUMENT");
        assertThat(result.getResultRefId()).isEqualTo(321L);
        assertThat(result.getOutput()).isEqualTo(Map.of(
                "documentId", 321L,
                "knowledgeBaseId", 55L,
                "backfilledChunkCount", 4
        ));
        verify(vectorIndexerService).backfillDocumentEmbeddings(321L, 55L);
    }

    @Test
    void shouldMarkBackfillFailedAndNotThrowWhenIndexerFallsBackToBm25Only() {
        Task task = new Task();
        task.setId(92L);
        task.setUserId(7L);
        task.setSpaceId(5L);
        task.setTaskType(TaskType.EMBEDDING_BACKFILL);
        task.setInputJson("""
                {"documentId":654,"knowledgeBaseId":55}
                """);

        when(vectorIndexerService.backfillDocumentEmbeddings(654L, 55L))
                .thenThrow(new IllegalStateException("embedding timeout"));

        TaskResult result = worker.execute(new TaskExecutionContext(task, new ObjectMapper(), null, null));

        assertThat(result.getResultRefType()).isEqualTo("DOCUMENT");
        assertThat(result.getResultRefId()).isEqualTo(654L);
        assertThat(result.getOutput()).isEqualTo(Map.of(
                "documentId", 654L,
                "knowledgeBaseId", 55L,
                "backfilledChunkCount", 0,
                "fallbackToBm25", true
        ));
        verify(vectorIndexerService).markBackfillFailed(654L, "embedding timeout");
        verify(vectorIndexerService, never()).switchAliasWhenReady(any());
    }
}
