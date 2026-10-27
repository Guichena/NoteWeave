package com.noteweave.artifact;

import java.util.List;

/**
 * C4 Artifact 编辑事件。
 *
 * <p>事件由编辑版本事务发布；Memory 侧（B-08）按 editEventId 幂等消费。
 * artifactVersionId 是新编辑版本，不是原生成 taskId；originTaskId 保留原任务身份。
 * beforeDigest/afterDigest 为全文 SHA-256，changedSections 为变更章节标题或序号。
 * 事件不携带正文全文，正文大小与日志输出都有上限。</p>
 */
public record ArtifactEditEvent(
        String editEventId,
        String workspaceId,
        String artifactJobId,
        String artifactVersionId,
        String baseVersionId,
        int baseVersionNo,
        String originTaskId,
        String actingUserId,
        String beforeDigest,
        String afterDigest,
        List<String> changedSections,
        int schemaVersion
) {
    public ArtifactEditEvent {
        changedSections = changedSections == null ? List.of()
                : changedSections.stream().limit(64).toList();
        schemaVersion = 2;
    }
}
