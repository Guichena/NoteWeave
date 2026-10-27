package com.noteweave.source.eligibility;

import java.util.List;
import java.util.Set;

/**
 * 来源资格状态矩阵的纯函数实现。
 *
 * <p>矩阵对应总计划的默认产品规则：
 * <ul>
 *   <li>READY：历史可读、当前证据可用、依赖闭包通过后允许再发布。</li>
 *   <li>暂不可用：历史保留并说明，当前证据与再发布阻断，但属于可恢复原因。</li>
 *   <li>USER 删除：历史 Note 正文保留，证据与再发布禁止。</li>
 *   <li>COMPLIANCE 撤销：历史版本也不可绕过，正文与文件不得继续暴露。</li>
 *   <li>内部缺失/跨工作台：不泄露原文与来源存在性。</li>
 * </ul>
 * 数据库或 schema 异常不能在这里转换成允许。</p>
 */
public final class MaterialEligibilityPolicy {

    /**
     * 历史用途下仍必须阻断的依赖失败后缀：合规撤销/缺失/越权/未知、回路与预算超限、
     * 依赖关系解析失败标记，以及 USER 删除（正文保留政策未确认前按保守默认阻断）。
     * 暂不可用/快照过期不在此列：历史正文保留并说明原因。
     */
    private static final Set<String> HISTORY_HARD_FAILURE_SUFFIXES = Set.of(
            "USER_DELETED", "COMPLIANCE_REVOKED", "MISSING_INTERNAL", "FOREIGN_WORKSPACE",
            "UNKNOWN_SCHEMA", "DEPENDENCY_CYCLE", "DEPENDENCY_DEPTH_EXCEEDED", "DEPENDENCY_BUDGET_EXCEEDED",
            "DEPENDENCY_HISTORY_BLOCKED", "SCOPE_PARSE_FAILED", "RUN_UNRESOLVED", "DEPENDENCIES_UNKNOWN",
            "GENERATION_REF_INVALID", "GENERATION_KIND_UNKNOWN", "VERSION_UNRESOLVED",
            "CITATIONS_PARSE_FAILED");

    private MaterialEligibilityPolicy() {
    }

    /** 由持久化的 source 状态计算判定；调用方需先把行状态映射为 EligibilityStatus。 */
    public static EligibilityDecision decide(
            String originKey,
            EligibilityStatus status,
            String reasonCode,
            Long sourceVersion,
            String snapshotId,
            List<String> dependencySummary
    ) {
        boolean historyReadable;
        boolean evidenceEligible;
        boolean republishAllowed;
        switch (status) {
            case READY -> {
                historyReadable = true;
                evidenceEligible = true;
                republishAllowed = dependencySummary.isEmpty();
            }
            case TEMPORARILY_UNAVAILABLE, STALE_SNAPSHOT -> {
                historyReadable = true;
                evidenceEligible = false;
                republishAllowed = false;
            }
            case USER_DELETED -> {
                historyReadable = true;
                evidenceEligible = false;
                republishAllowed = false;
            }
            case COMPLIANCE_REVOKED, MISSING_INTERNAL, FOREIGN_WORKSPACE, UNKNOWN_SCHEMA -> {
                historyReadable = false;
                evidenceEligible = false;
                republishAllowed = false;
            }
            default -> throw new IllegalArgumentException("Unknown eligibility status: " + status);
        }
        return new EligibilityDecision(originKey, status, reasonCode, sourceVersion, snapshotId,
                historyReadable, evidenceEligible, republishAllowed, dependencySummary);
    }

    /** READY 来源存在未通过的依赖项时降级为依赖失败，保留依赖摘要供原因展示。 */
    public static EligibilityDecision withDependencyFailure(EligibilityDecision ready, List<String> failures) {
        if (ready.status() != EligibilityStatus.READY) {
            return ready;
        }
        List<String> summary = failures == null ? List.of() : List.copyOf(failures);
        return new EligibilityDecision(ready.originKey(), ready.status(), "DEPENDENCY_NOT_READY",
                ready.sourceVersion(), ready.snapshotId(),
                ready.historyReadable(), false, false, summary);
    }

    /** 把 source 行的持久化状态映射为资格状态；deleteReason 区分 USER 与 COMPLIANCE。 */
    public static EligibilityStatus statusOf(String sourceStatus, String deleteReason) {
        if (sourceStatus == null) {
            return EligibilityStatus.MISSING_INTERNAL;
        }
        if ("DELETED".equals(sourceStatus)) {
            return "COMPLIANCE".equals(deleteReason)
                    ? EligibilityStatus.COMPLIANCE_REVOKED
                    : EligibilityStatus.USER_DELETED;
        }
        if ("READY".equals(sourceStatus)) {
            return EligibilityStatus.READY;
        }
        return EligibilityStatus.TEMPORARILY_UNAVAILABLE;
    }

    /**
     * 该状态下历史正文默认可读（矩阵中 historyReadable=true 的状态集合）。
     * NR04：生成来源处于这些状态时同样需要解析依赖闭包——上游硬撤销必须能阻断历史展示。
     */
    public static boolean historyReadableByStatus(EligibilityStatus status) {
        return status == EligibilityStatus.READY
                || status == EligibilityStatus.TEMPORARILY_UNAVAILABLE
                || status == EligibilityStatus.STALE_SNAPSHOT
                || status == EligibilityStatus.USER_DELETED;
    }

    /**
     * 只附加依赖摘要、不改变可读性标志（NR04）：
     * 供非 READY 状态（证据位已为 false）携带闭包事实，供历史硬失败判定与原因展示使用。
     */
    public static EligibilityDecision withDependencySummary(EligibilityDecision decision, List<String> failures) {
        List<String> summary = failures == null ? List.of() : List.copyOf(failures);
        if (summary.isEmpty() || decision.dependencySummary().equals(summary)) {
            return decision;
        }
        return new EligibilityDecision(decision.originKey(), decision.status(), decision.reasonCode(),
                decision.sourceVersion(), decision.snapshotId(),
                decision.historyReadable(), decision.evidenceEligible(),
                decision.republishAllowed(), summary);
    }

    /** 依赖失败摘要是否属于历史用途也必须阻断的硬失败。 */
    public static boolean isHardFailureForHistory(String failure) {
        if (failure == null) {
            return false;
        }
        int separator = failure.lastIndexOf(':');
        String suffix = separator >= 0 ? failure.substring(separator + 1) : failure;
        return HISTORY_HARD_FAILURE_SUFFIXES.contains(suffix);
    }

    /**
     * 历史用途下依赖闭包存在硬失败时，历史正文一并不可读。
     * 暂不可用/快照过期类软失败不改变 historyReadable。
     */
    public static EligibilityDecision withHistoryHardFailure(EligibilityDecision decision) {
        if (!decision.historyReadable()) {
            return decision;
        }
        boolean hard = decision.dependencySummary().stream()
                .anyMatch(MaterialEligibilityPolicy::isHardFailureForHistory);
        if (!hard) {
            return decision;
        }
        return new EligibilityDecision(decision.originKey(), decision.status(), "DEPENDENCY_HISTORY_BLOCKED",
                decision.sourceVersion(), decision.snapshotId(),
                false, decision.evidenceEligible(), decision.republishAllowed(),
                decision.dependencySummary());
    }
}
