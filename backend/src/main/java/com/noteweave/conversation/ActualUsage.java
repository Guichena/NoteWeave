package com.noteweave.conversation;

/**
 * C5 供应商真实 usage。
 *
 * <p>reported=false 表示供应商没有返回 usage（取消、断流或协议不支持），
 * 计数保持 null 语义（这里用 -1 表达未知），不能把预估值伪造成真实用量。</p>
 */
public record ActualUsage(
        String modelId,
        Long inputTokens,
        Long outputTokens,
        boolean reported,
        Completeness completeness
) {
    public enum Completeness { COMPLETE, PARTIAL, UNKNOWN }

    public static ActualUsage unreported(String modelId, Completeness completeness) {
        return new ActualUsage(modelId, null, null, false, completeness);
    }

    public static ActualUsage reported(String modelId, long inputTokens, long outputTokens) {
        return new ActualUsage(modelId, inputTokens, outputTokens, true, Completeness.COMPLETE);
    }
}
