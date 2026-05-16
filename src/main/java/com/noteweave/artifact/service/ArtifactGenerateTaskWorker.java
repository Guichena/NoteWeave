package com.noteweave.artifact.service;

import com.noteweave.task.model.TaskType;
import com.noteweave.task.worker.TaskExecutionContext;
import com.noteweave.task.worker.TaskResult;
import com.noteweave.task.worker.TaskWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ArtifactGenerateTaskWorker implements TaskWorker {

    private final ArtifactPlanExecutor artifactPlanExecutor;

    @Override
    public TaskType taskType() {
        return TaskType.ARTIFACT_GENERATE;
    }

    @Override
    public TaskResult execute(TaskExecutionContext context) {
        ArtifactPlanExecutor.ArtifactExecutionResult result = artifactPlanExecutor.execute(context);
        return TaskResult.builder()
                .output(result.output())
                .resultRefType(result.artifactVersionId() == null ? null : "ARTIFACT_VERSION")
                .resultRefId(result.artifactVersionId())
                .build();
    }
}
