package com.noteweave.personal.compiler.dto;

import com.noteweave.personal.source.model.SourceCompileStatus;
import com.noteweave.task.model.TaskStatus;
import lombok.Builder;

@Builder
public record CompileSourceResponse(
        Long sourceId,
        Long taskId,
        TaskStatus taskStatus,
        SourceCompileStatus compileStatus
) {
}
