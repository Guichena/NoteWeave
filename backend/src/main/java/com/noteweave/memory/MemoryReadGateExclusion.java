package com.noteweave.memory;

/**
 * MEM-05：被记忆读路径排除的一条记忆，带上原因码与说明。
 * <p>
 * {@code reasonCode} 区分「暂不支持」（{@code WIKI_SOURCE_NOT_SUPPORTED}，等 C1 端口表达知识项语义后
 * 自动恢复）与真实不可读（{@code SOURCE_NOT_READY}、{@code SOURCE_NOT_ELIGIBLE}）。
 */
public record MemoryReadGateExclusion(
        String memoryItemId,
        String revisionId,
        String displayText,
        String itemStatus,
        String reasonCode,
        String reasonText
) {
}
