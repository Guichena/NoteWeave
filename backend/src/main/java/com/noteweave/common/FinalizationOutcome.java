package com.noteweave.common;

/**
 * 死信补偿收尾的统一语义结果（C2）。
 *
 * <ul>
 *   <li>{@link #APPLIED}：业务收尾已提交，效果幂等可重放；</li>
 *   <li>{@link #IGNORED_STALE}：目标不存在或代次已过期，无副作用跳过；</li>
 *   <li>{@link #RETRYABLE_FAILURE}：暂时性失败（数据库、依赖暂不可用），可退避重试；</li>
 *   <li>{@link #TERMINAL_FAILURE}：达到重试上限或不可恢复，保持可查询与告警，不假装完成。</li>
 * </ul>
 */
public enum FinalizationOutcome {
    APPLIED,
    IGNORED_STALE,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE;

    public boolean success() {
        return this == APPLIED || this == IGNORED_STALE;
    }
}
