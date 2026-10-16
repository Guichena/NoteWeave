package com.noteweave.source;

/**
 * 资料的当前快照已完成解析与检索索引，状态进入 READY。
 * 依赖资料可读的下游（例如 Wiki 编译）在事务提交后据此开始，避免在解析或索引尚未完成时执行。
 */
public record SourceRetrievalReadyEvent(String workspaceId, String sourceId) {
}
