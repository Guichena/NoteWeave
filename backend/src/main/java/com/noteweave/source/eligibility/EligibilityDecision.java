package com.noteweave.source.eligibility;

import java.util.List;

/**
 * 单个来源引用的资格判定结果。
 *
 * <p>不返回 boolean：逐项原因、来源版本/快照和依赖摘要让调用方能区分
 * 暂不可用、用户删除、合规撤销、内部缺失等不同语义。</p>
 */
public record EligibilityDecision(
        String originKey,
        EligibilityStatus status,
        String reasonCode,
        Long sourceVersion,
        String snapshotId,
        boolean historyReadable,
        boolean evidenceEligible,
        boolean republishAllowed,
        List<String> dependencySummary
) {
    public EligibilityDecision {
        dependencySummary = dependencySummary == null ? List.of() : List.copyOf(dependencySummary);
    }

    public static EligibilityDecision ready(String originKey, Long sourceVersion, String snapshotId) {
        return new EligibilityDecision(originKey, EligibilityStatus.READY, "READY",
                sourceVersion, snapshotId, true, true, true, List.of());
    }

    public boolean allowed(AccessPurpose purpose) {
        return switch (purpose) {
            case HISTORY_DISPLAY -> historyReadable;
            case CURRENT_EVIDENCE -> evidenceEligible;
            case REPUBLISH -> republishAllowed;
        };
    }
}
