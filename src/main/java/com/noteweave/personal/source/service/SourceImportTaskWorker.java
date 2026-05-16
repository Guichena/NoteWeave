package com.noteweave.personal.source.service;

import com.noteweave.task.model.TaskType;
import com.noteweave.task.worker.TaskExecutionContext;
import com.noteweave.task.worker.TaskResult;
import com.noteweave.task.worker.TaskWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SourceImportTaskWorker implements TaskWorker {

    private final SourceImportService sourceImportService;

    @Override
    public TaskType taskType() {
        return TaskType.SOURCE_IMPORT;
    }

    @Override
    public TaskResult execute(TaskExecutionContext context) {
        SourceImportService.SourceImportResult result = sourceImportService.importSource(context.task());
        return TaskResult.builder()
                .output(result.output())
                .resultRefType(result.resultSourceId() == null ? null : "SOURCE")
                .resultRefId(result.resultSourceId())
                .build();
    }
}
