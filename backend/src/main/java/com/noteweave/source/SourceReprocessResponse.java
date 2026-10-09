package com.noteweave.source;

/** 重新处理的结果：新任务 ID，以及从哪个阶段重新开始（PARSE 从解析开始，INDEX 从向量化开始）。 */
public record SourceReprocessResponse(String sourceId, String taskId, String restartFrom) {
}
