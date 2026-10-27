package com.noteweave.knowledge;

/**
 * C2 Wiki 摄取任务入队端口（A 定义调用合同，B 提供基于 TaskOutboxEnqueuer 的实现）。
 *
 * <p>语义身份为 workspace/source/snapshot/ingestPolicyVersion/stage(业务阶段)。
 * 实现先按语义身份确定唯一业务认领，再创建 task 与 task_outbox 行（同一业务事务）；
 * 命中既有有效语义记录时返回既有 taskId（created=false），不会产生第二个孤立任务。
 * payload 由实现按命令字段稳定生成，不能因随机 taskId 造成同语义异载荷冲突。</p>
 */
public interface WikiIngestTaskEnqueuerPort {

    /**
     * 语义幂等地创建或复用 Wiki 摄取任务（含 task 行与 outbox 行）。
     *
     * @throws IllegalStateException 当前没有活动事务
     */
    EnqueueOutcome enqueueIngest(IngestCommand command);

    /** 摄取入队命令；字段共同决定语义身份与稳定载荷。 */
    record IngestCommand(
            String workspaceId,
            String sourceId,
            String snapshotId,
            String ingestPolicyVersion,
            String operation,
            String message
    ) {
    }

    /** 入队结果；created=false 表示命中既有语义记录并复用其任务。 */
    record EnqueueOutcome(String taskId, boolean created) {
    }
}
