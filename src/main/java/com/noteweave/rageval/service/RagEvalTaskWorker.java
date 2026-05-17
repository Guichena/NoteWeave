package com.noteweave.rageval.service;

import com.noteweave.task.model.TaskType;
import com.noteweave.task.worker.TaskExecutionContext;
import com.noteweave.task.worker.TaskResult;
import com.noteweave.task.worker.TaskWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RagEvalTaskWorker implements TaskWorker {

    private final RagEvaluationService ragEvaluationService;

    @Override
    public TaskType taskType() {
        return TaskType.RAG_EVAL_RUN;
    }

    @Override
    public TaskResult execute(TaskExecutionContext context) {
        RagEvalTaskInput input = context.readInput(RagEvalTaskInput.class);
        ragEvaluationService.executeRun(input.runId(), context.task().getId());
        return TaskResult.success(java.util.Map.of("runId", input.runId()));
    }
}
