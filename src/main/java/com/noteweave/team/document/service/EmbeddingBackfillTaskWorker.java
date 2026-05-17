package com.noteweave.team.document.service;

import com.noteweave.task.model.TaskType;
import com.noteweave.task.worker.TaskExecutionContext;
import com.noteweave.task.worker.TaskResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmbeddingBackfillTaskWorker implements com.noteweave.task.worker.TaskWorker {

    private final VectorIndexerService vectorIndexerService;

    @Override
    public TaskType taskType() {
        return TaskType.EMBEDDING_BACKFILL;
    }

    @Override
    public TaskResult execute(TaskExecutionContext context) {
        EmbeddingBackfillInput input = context.readInput(EmbeddingBackfillInput.class);
        try {
            int backfilled = vectorIndexerService.backfillDocumentEmbeddings(input.documentId(), input.knowledgeBaseId());
            return TaskResult.builder()
                    .resultRefType("DOCUMENT")
                    .resultRefId(input.documentId())
                    .output(Map.of(
                            "documentId", input.documentId(),
                            "knowledgeBaseId", input.knowledgeBaseId(),
                            "backfilledChunkCount", backfilled
                    ))
                    .build();
        } catch (Exception ex) {
            vectorIndexerService.markBackfillFailed(input.documentId(), ex.getMessage());
            return TaskResult.builder()
                    .resultRefType("DOCUMENT")
                    .resultRefId(input.documentId())
                    .output(Map.of(
                            "documentId", input.documentId(),
                            "knowledgeBaseId", input.knowledgeBaseId(),
                            "backfilledChunkCount", 0,
                            "fallbackToBm25", true
                    ))
                    .build();
        }
    }

    public record EmbeddingBackfillInput(Long documentId, Long knowledgeBaseId) {
    }
}
