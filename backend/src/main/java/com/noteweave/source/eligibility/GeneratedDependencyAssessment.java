package com.noteweave.source.eligibility;

import java.util.List;
import java.util.Map;

/**
 * 生成来源依赖闭包的结构化评估结果。
 *
 * <p>resolved=false 表示依赖关系本身无法解析（运行不存在/越权、范围 JSON 损坏、
 * 未知生成类型、缺少可信依赖记录），调用方必须按阻断处理，不能把解析失败
 * 当作没有依赖。blockingReasons 携带稳定原因码；dependencies 是已解析依赖的逐项判定。</p>
 */
public record GeneratedDependencyAssessment(
        boolean resolved,
        List<String> blockingReasons,
        Map<String, EligibilityDecision> dependencies
) {
    public GeneratedDependencyAssessment {
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        dependencies = dependencies == null ? Map.of() : Map.copyOf(dependencies);
    }

    /** 依赖关系可解析且所有依赖满足指定用途时为 true。 */
    public boolean allAllowed(AccessPurpose purpose) {
        if (!resolved) {
            return false;
        }
        return dependencies.values().stream().allMatch(decision -> decision.allowed(purpose));
    }
}
