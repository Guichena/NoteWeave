package com.noteweave.team.wiki.service;

import com.noteweave.task.model.TaskType;
import com.noteweave.task.worker.TaskExecutionContext;
import com.noteweave.task.worker.TaskResult;
import com.noteweave.task.worker.TaskWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WikiIndexTaskWorker implements TaskWorker {

    private final WikiIndexService wikiIndexService;

    @Override
    public TaskType taskType() {
        return TaskType.WIKI_INDEX;
    }

    @Override
    public TaskResult execute(TaskExecutionContext context) {
        return wikiIndexService.executeIndexTask(context);
    }
}
