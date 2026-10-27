package com.noteweave.source.eligibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C1 来源资格事实端口。
 *
 * <p>所有需要判断来源可读性的入口（Note 详情/历史/聊天、Wiki、Memory、Research 发布、Artifact）
 * 统一经由本端口获取逐项资格事实，不允许各自复制一套 SQL 或全局绕过状态。
 * 批量评估有数量、深度、节点与查询预算；递归生成来源按当前路径做回路检测，
 * 超限返回明确的 DEPENDENCY_DEPTH_EXCEEDED / DEPENDENCY_BUDGET_EXCEEDED，不能按 READY 放行。</p>
 */
public interface MaterialEligibilityPort {

    /** 单次评估的来源数量上限，防止批量请求膨胀成全库扫描。 */
    int MAX_ORIGINS_PER_ASSESS = 200;

    /** 生成来源依赖闭包的单条路径最大递归深度，防止循环引用与恶意嵌套。 */
    int MAX_DEPENDENCY_DEPTH = 8;

    /**
     * 批量评估来源资格。
     *
     * <p>生成来源在任何用途下都会解析依赖闭包：CURRENT_EVIDENCE/REPUBLISH 要求
     * 依赖当前可用；HISTORY_DISPLAY 只放宽叶子的暂不可用/快照过期，
     * 传递 COMPLIANCE/缺失/越权仍会撤销历史正文可读性。</p>
     *
     * @return 以 {@link OriginRef#originKey()} 为键的逐项判定；输入中的非法引用返回 MISSING_INTERNAL/UNKNOWN_SCHEMA
     * @throws IllegalArgumentException 输入数量超过上限
     */
    Map<String, EligibilityDecision> assess(String workspaceId, List<OriginRef> origins, AccessPurpose purpose);

    /** 单来源评估便捷方法。 */
    default EligibilityDecision assessOne(String workspaceId, OriginRef origin, AccessPurpose purpose) {
        Map<String, EligibilityDecision> results = assess(workspaceId, List.of(origin), purpose);
        EligibilityDecision decision = results.get(origin.originKey());
        if (decision == null) {
            throw new IllegalStateException("Eligibility assessment missed origin " + origin.originKey());
        }
        return decision;
    }

    /**
     * 结构化评估生成来源的依赖闭包（不评估生成来源自身）。
     *
     * <p>resolved=false 表示依赖关系无法解析：运行缺失/越权、范围 JSON 损坏、
     * 未知生成类型或缺少可信依赖记录。调用方必须阻断，禁止当作空依赖放行。
     * 闭包递归有界；无法解析的依赖以 UNKNOWN_SCHEMA/MISSING_INTERNAL 判定返回，不抛异常。</p>
     */
    GeneratedDependencyAssessment assessGeneratedDependenciesDetailed(
            String workspaceId, String generatedBy, String generatedRefId, AccessPurpose purpose);

    /**
     * 兼容视图：把结构化结果投影为逐依赖判定表。
     *
     * <p>无法解析时以 research-run:/generated: 前缀的哨兵键携带 UNKNOWN_SCHEMA 阻断判定，
     * 保证旧调用方在范围损坏或运行缺失时同样得到不允许的结论。</p>
     */
    default Map<String, EligibilityDecision> assessGeneratedDependencies(
            String workspaceId, String generatedBy, String generatedRefId, AccessPurpose purpose) {
        GeneratedDependencyAssessment detailed =
                assessGeneratedDependenciesDetailed(workspaceId, generatedBy, generatedRefId, purpose);
        Map<String, EligibilityDecision> result = new LinkedHashMap<>(detailed.dependencies());
        for (String reason : detailed.blockingReasons()) {
            result.putIfAbsent(reason, MaterialEligibilityPolicy.decide(reason,
                    EligibilityStatus.UNKNOWN_SCHEMA, reason, null, null, List.of()));
        }
        return result;
    }
}
