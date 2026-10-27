package com.noteweave.artifact;

import java.util.List;

/**
 * C4 v2 产物编辑事件的持久存储端口（窄表 artifact_edit_event）。
 *
 * <p>事件行与编辑版本在同一短事务内提交：版本插入后事件写入失败必须回滚版本。
 * 业务身份为 (workspaceId, artifactJobId, requestId) 唯一；重复请求以持久行判定重放，
 * 不再使用 JSON LIKE 匹配。投递状态字段（READY/IN_FLIGHT/SENT/DEAD_LETTER、attempt、
 * generation、leaseOwner、claimToken、leaseUntil、dueAt、lastError、sentAt）由 B 侧
 * 投递适配器管理；本端口只写入业务字段并把投递状态初始化为 READY。</p>
 */
public interface ArtifactEditEventStore {

    /**
     * 在当前版本事务内插入事件行；必须与 appendEditVersion 同事务调用。
     *
     * @throws IllegalStateException 当前没有活动事务
     * @throws org.springframework.dao.DuplicateKeyException 同身份事件已存在（并发重复提交）
     */
    void insertEvent(ArtifactEditEventRecord event);

    /** 按 workspace/job/requestId 查找既有事件；不存在返回 null。 */
    ArtifactEditEventRecord findByRequestId(String workspaceId, String artifactJobId, String requestId);

    /** C4 v2 事件业务字段；schemaVersion=2。 */
    record ArtifactEditEventRecord(
            String editEventId,
            String workspaceId,
            String artifactJobId,
            String requestId,
            String requestDigest,
            String artifactVersionId,
            String baseVersionId,
            int baseVersionNo,
            String originTaskId,
            String actingUserId,
            String beforeDigest,
            String afterDigest,
            List<String> changedSectionIds,
            List<ArtifactVersionDiffV2.Fragment> changedFragments,
            int changedChars,
            int totalChars,
            String diffAlgorithm,
            String payloadJson,
            String eventPayloadDigest,
            String createdAt
    ) {
        public ArtifactEditEventRecord {
            changedSectionIds = changedSectionIds == null ? List.of() : List.copyOf(changedSectionIds);
            changedFragments = changedFragments == null ? List.of() : List.copyOf(changedFragments);
        }
    }
}
