package com.noteweave.personal.source.service;

import com.noteweave.personal.compiler.service.WikiCompilerService;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.worker.TaskExecutionContext;
import com.noteweave.task.worker.TaskResult;
import com.noteweave.task.worker.TaskWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SourceCompileTaskWorker implements TaskWorker {

    private final WikiCompilerService wikiCompilerService;

    @Override
    public TaskType taskType() {
        return TaskType.SOURCE_COMPILE;
    }

    @Override
    public TaskResult execute(TaskExecutionContext context) {
        WikiCompilerService.CompileTaskResult result = wikiCompilerService.executeCompileTask(context.task());
        return TaskResult.builder()
                .output(result.output())
                .resultRefType(result.articleCardId() == null ? null : "ARTICLE_CARD")
                .resultRefId(result.articleCardId())
                .build();
    }
}
