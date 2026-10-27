package com.noteweave.knowledge;

/** Wiki READY 意图处理结果；与 C2 收尾结果语义对齐。 */
public enum WikiIngestIntentOutcome {
    /** 已入队或已有同身份有效任务，业务效果成立。 */
    APPLIED,
    /** 快照已被取代或来源已删除，忽略且无需重试。功能开关关闭不再归入此终态（AU-003）。 */
    IGNORED_STALE,
    /** 暂时无法处理（来源仍在处理中等），需要按重试策略再次投递。 */
    RETRYABLE_FAILURE,
    /**
     * 功能整体关闭（AU-003）：意图停放等待重新启用，不终态化也不消耗重试预算。
     * 调度器按长延迟再投；与 RETRYABLE_FAILURE 的区别是关闭状态可能持续很久，
     * 不能用有限次退避冒充暂停。
     */
    PARKED
}
