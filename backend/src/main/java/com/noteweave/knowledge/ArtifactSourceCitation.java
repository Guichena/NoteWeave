package com.noteweave.knowledge;

/**
 * 产物写回知识库时的一条来源引用（KB-01）。
 *
 * <p>产物引用按来源级别绑定：冻结来源加生成时的快照，不强行指向具体分块，
 * 因此 {@code sourceChunkId} 不在这个记录里。{@code quoteText} 为空表示只登记来源依赖，
 * 没有可展示的原文引用。</p>
 */
public record ArtifactSourceCitation(
        String sourceId,
        String sourceSnapshotId,
        String title,
        String quoteText,
        String location
) {
}
