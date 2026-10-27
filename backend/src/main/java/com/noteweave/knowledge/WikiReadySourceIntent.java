package com.noteweave.knowledge;

/**
 * C3 READY 摄取意图（A 侧消费契约）。
 *
 * <p>身份字段为 workspace/source/snapshot/generation/ingestPolicyVersion：
 * 同一快照同一策略只允许产生一个有效 Wiki 摄取任务。生产端由 B 在 source READY
 * 提交事务内持久化；本 DTO 由双方共享，字段变更需升版本并同步 B。</p>
 */
public record WikiReadySourceIntent(
        String intentId,
        String workspaceId,
        String sourceId,
        String snapshotId,
        long generation,
        String ingestPolicyVersion
) {
    public WikiReadySourceIntent {
        intentId = intentId == null || intentId.isBlank() ? "" : intentId.trim();
        ingestPolicyVersion = ingestPolicyVersion == null || ingestPolicyVersion.isBlank()
                ? "default" : ingestPolicyVersion.trim();
    }

    public boolean isValid() {
        return workspaceId != null && !workspaceId.isBlank()
                && sourceId != null && !sourceId.isBlank()
                && snapshotId != null && !snapshotId.isBlank();
    }
}
