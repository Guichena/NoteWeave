package com.noteweave.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预算裁决：把模型窗口换算成输入硬上限，再把硬上限分配给各层。
 *
 * <p>分配顺序固定：先满足必带层，再按调用点声明的层目标分配可选材料，剩余预算不强行塞满。
 * 必带层本身超过硬上限时不伪称完整保留，返回明确结果码，由调用方决定降级还是拒绝。</p>
 */
public final class ContextBudgetPlanner {

    /** 必带层已超过输入硬上限。 */
    public static final String MANDATORY_OVER_BUDGET = "CONTEXT_MANDATORY_OVER_BUDGET";
    /** 模型窗口扣除输出预留与安全余量后没有可用输入预算。 */
    public static final String WINDOW_EXHAUSTED = "CONTEXT_WINDOW_EXHAUSTED";
    /** 分配成功。 */
    public static final String OK = "OK";

    /** 按调用点与模型窗口算出预算。输出预留取调用点默认值与实际最大输出的较大者。 */
    public ContextBudget plan(ContextCallSite callSite, int modelWindowTokens, int maximumOutputTokens) {
        if (callSite == null || modelWindowTokens < 1 || maximumOutputTokens < 0) {
            throw new IllegalArgumentException("budget planning requires a call site and a window");
        }
        int outputReserve = Math.max(callSite.outputReserveTokens(), maximumOutputTokens);
        int hardLimit = modelWindowTokens - outputReserve - callSite.safetyMarginTokens();
        if (hardLimit < 1) {
            throw new IllegalArgumentException(WINDOW_EXHAUSTED + ": window leaves no input budget");
        }
        return ContextBudget.of(modelWindowTokens, outputReserve, callSite.safetyMarginTokens(),
                callSite.targetTokens(), ContextRenderer.estimatorVersion());
    }

    /**
     * 把输入硬上限分配给各层。
     *
     * @param contract       已冻结的契约，提供硬上限与渲染顺序
     * @param mandatoryTokens 必带层实际需要的 token，没有实际值的必带层按调用点目标估算
     * @return 分层目标与结果码
     */
    public Allocation allocate(ContextContract contract, Map<ContextLayer, Integer> mandatoryTokens) {
        if (contract == null) {
            throw new IllegalArgumentException("allocation requires a contract");
        }
        Map<ContextLayer, Integer> required = mandatoryTokens == null ? Map.of() : mandatoryTokens;
        Map<ContextLayer, Integer> allocated = new LinkedHashMap<>();
        int remaining = contract.hardLimitTokens();
        long mandatoryTotal = 0;
        for (ContextLayer layer : contract.ordering()) {
            if (!contract.isMandatory(layer)) {
                continue;
            }
            int need = required.getOrDefault(layer, contract.callSite().layerTargets()
                    .getOrDefault(layer, 0));
            mandatoryTotal += need;
            allocated.put(layer, need);
        }
        if (mandatoryTotal > contract.hardLimitTokens()) {
            return new Allocation(Collections.unmodifiableMap(allocated),
                    (int) Math.min(mandatoryTotal, Integer.MAX_VALUE), true, MANDATORY_OVER_BUDGET);
        }
        remaining -= (int) mandatoryTotal;
        for (ContextLayer layer : contract.ordering()) {
            if (contract.isMandatory(layer)) {
                continue;
            }
            int target = contract.callSite().layerTargets().getOrDefault(layer, 0);
            int grant = Math.min(Math.max(target, 0), remaining);
            allocated.put(layer, grant);
            remaining -= grant;
        }
        int total = 0;
        for (int value : allocated.values()) {
            total += value;
        }
        return new Allocation(Collections.unmodifiableMap(allocated), total, false, OK);
    }

    /** 契约里所有允许层的目标，按渲染顺序返回，未分配的层目标为 0。 */
    public Map<ContextLayer, Integer> layerTargets(ContextContract contract) {
        Map<ContextLayer, Integer> targets = new LinkedHashMap<>();
        for (ContextLayer layer : contract.ordering()) {
            targets.put(layer, contract.callSite().layerTargets().getOrDefault(layer, 0));
        }
        return Collections.unmodifiableMap(targets);
    }

    /**
     * 分配结果。
     *
     * @param layerTargets  各层目标
     * @param allocatedTokens 分配总量
     * @param overBudget    必带层是否已经超预算
     * @param outcomeCode   稳定结果码
     */
    public record Allocation(
            Map<ContextLayer, Integer> layerTargets,
            int allocatedTokens,
            boolean overBudget,
            String outcomeCode
    ) {
        public Allocation {
            layerTargets = layerTargets == null ? Map.of() : layerTargets;
        }

        /** 某层目标，未分配时为 0。 */
        public int targetOf(ContextLayer layer) {
            return layerTargets.getOrDefault(layer, 0);
        }

        /** 按渲染顺序返回已分配层的列表，便于诊断输出。 */
        public List<ContextLayer> layers() {
            return List.copyOf(layerTargets.keySet());
        }
    }
}
