package com.noteweave.conversation;

/**
 * C5 token 预估入口。
 *
 * <p>当前没有模型专属分词器，统一沿用字符折算的保守估计并标记 legacy；
 * 引入真实分词器时新增 method/tokenizerVersion，不改既有调用的语义。</p>
 */
public final class TokenCounter {

    private TokenCounter() {
    }

    public static TokenEstimate estimate(String text, String modelProfile) {
        return TokenEstimate.legacy(ContextTokenEstimator.estimate(text), modelProfile);
    }

    /** 便捷入口：估算条数并保留保守 margin。 */
    public static int estimateCountWithMargin(String text, String modelProfile, double marginRatio) {
        TokenEstimate estimate = estimate(text, modelProfile);
        double ratio = Math.max(0.0d, marginRatio);
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(estimate.count() * (1.0d + ratio)));
    }
}
