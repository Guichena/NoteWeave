package com.noteweave.context;

/**
 * 一次调用的总预算。
 *
 * <p>模型上下文窗口是最终硬上限，必须同时计入系统规则、问题、上下文、前提、证据、协议包装、
 * 输出预留与安全余量。这里区分三件事：模型窗口、输出预留与输入硬上限。各层目标相加不等于
 * 可以发送的总量，所以预算只能从硬上限往下分配，不能把各层上限分别装满再拼接。</p>
 *
 * @param modelWindowTokens   模型上下文窗口
 * @param outputReserveTokens 输出预留
 * @param safetyMarginTokens  估算误差与包装的安全余量
 * @param hardLimitTokens     输入硬上限，等于模型窗口扣除输出预留与安全余量
 * @param targetTokens        本次调用的目标预算，软目标，不超过硬上限
 * @param estimatorVersion    token 估算方式版本
 */
public record ContextBudget(
        int modelWindowTokens,
        int outputReserveTokens,
        int safetyMarginTokens,
        int hardLimitTokens,
        int targetTokens,
        String estimatorVersion
) {

    public ContextBudget {
        if (modelWindowTokens < 1 || outputReserveTokens < 0 || safetyMarginTokens < 0
                || hardLimitTokens < 0 || targetTokens < 0 || blank(estimatorVersion)) {
            throw new IllegalArgumentException("context budget is invalid");
        }
        if (hardLimitTokens != modelWindowTokens - outputReserveTokens - safetyMarginTokens) {
            throw new IllegalArgumentException("hard limit must equal window minus reserves");
        }
        if (targetTokens > hardLimitTokens) {
            throw new IllegalArgumentException("target must not exceed the hard limit");
        }
    }

    /** 构造预算，硬上限由窗口、输出预留与安全余量算出。 */
    public static ContextBudget of(int modelWindowTokens, int outputReserveTokens,
                                   int safetyMarginTokens, int targetTokens, String estimatorVersion) {
        int hardLimit = modelWindowTokens - outputReserveTokens - safetyMarginTokens;
        int capped = Math.min(Math.max(targetTokens, 0), Math.max(hardLimit, 0));
        return new ContextBudget(modelWindowTokens, outputReserveTokens, safetyMarginTokens,
                hardLimit, capped, estimatorVersion);
    }

    /** 输入是否仍在硬上限内。 */
    public boolean admits(int inputTokens) {
        return inputTokens <= hardLimitTokens;
    }

    /** 输入是否仍在目标预算内，用于标记降级而不是失败。 */
    public boolean withinTarget(int inputTokens) {
        return inputTokens <= targetTokens;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
