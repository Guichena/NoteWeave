package com.noteweave.conversation;

import java.time.Instant;
import java.util.Optional;

/**
 * C5 真实 usage 持久存储端口（A 定义事实与写入合同，B 提供 JDBC 实现与迁移）。
 *
 * <p>身份键是单次请求尝试的 attemptId：在一次尝试开始时生成并稳定复用，
 * 不用 sessionId 作为唯一事实键。同 attemptId 幂等：首次写入生效，
 * 之后只允许受控升级——COMPLETE 可覆盖 PARTIAL/UNKNOWN；
 * 同等级不同值拒绝（调用方告警）；UNKNOWN 不覆盖任何既有记录；
 * 不累加重复终末帧。</p>
 */
public interface ActualUsageStore {

    /** 幂等写入；attemptId 已有记录时返回 false 且不修改既有事实。 */
    boolean insertIfAbsent(String attemptId, String workspaceId, ActualUsage usage, Instant occurredAt);

    /** 读取既有记录；不存在返回空。 */
    Optional<ActualUsage> find(String attemptId);

    /**
     * 受控升级：仅当新记录的完整度严格高于既有记录（COMPLETE &gt; PARTIAL &gt; UNKNOWN）
     * 时覆盖；同等级或降级（含迟到 UNKNOWN 覆盖 COMPLETE）返回 false 且不修改。
     */
    boolean upgradeIfSuperior(String attemptId, String workspaceId, ActualUsage usage, Instant occurredAt);
}
