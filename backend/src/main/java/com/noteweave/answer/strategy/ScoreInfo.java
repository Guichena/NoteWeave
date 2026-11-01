package com.noteweave.answer.strategy;

/**
 * 证据的评分来源。
 *
 * <p>排序分可能来自三种地方：专用重排服务、对话模型后备打分、融合阶段本身。三者的量纲和可信度
 * 不同，混在一个 {@code rerankScore} 里就无法判断「这个分数能不能用来降级」。这里显式记录来源，
 * 充分性判定只对 {@link #PROVIDER_MODEL} 的分数应用门槛，其余来源只采用模型给出的结论。</p>
 *
 * <p>{@code value} 为空表示没有模型分数；{@code model} 记录实际给出分数的模型名，便于排查。</p>
 */
public record ScoreInfo(String origin, String model, Double value) {

    /** 专用重排服务给出的分数，唯一参与充分性门槛的来源。 */
    public static final String PROVIDER_MODEL = "PROVIDER_MODEL";
    /** 重排服务不可用时由对话模型给出的分数，量纲与重排服务一致，但不算提供方分数。 */
    public static final String LLM_FALLBACK = "LLM_FALLBACK";
    /** 重排未覆盖或整体降级，分数来自融合阶段，量级与重排分不同。 */
    public static final String FUSION_FALLBACK = "FUSION_FALLBACK";
    /** Wiki 种子的词法排名分。 */
    public static final String LEXICAL = "LEXICAL";
    /** Wiki 图扩展得到的页面，不参与排序分比较。 */
    public static final String GRAPH = "GRAPH";
    /** 未知来源。用于兼容历史证据与测试构造，不参与门槛。 */
    public static final String UNKNOWN = "UNKNOWN";

    public ScoreInfo {
        origin = origin == null || origin.isBlank() ? UNKNOWN : origin;
        model = model == null ? "" : model;
        value = value == null || !Double.isFinite(value) ? null : value;
    }

    public static ScoreInfo unknown() {
        return new ScoreInfo(UNKNOWN, "", null);
    }

    public static ScoreInfo fusion() {
        return new ScoreInfo(FUSION_FALLBACK, "", null);
    }

    public static ScoreInfo graph() {
        return new ScoreInfo(GRAPH, "", null);
    }

    public static ScoreInfo provider(String model, double value) {
        return new ScoreInfo(PROVIDER_MODEL, model, value);
    }

    public static ScoreInfo llmFallback(String model, double value) {
        return new ScoreInfo(LLM_FALLBACK, model, value);
    }

    public static ScoreInfo lexical(String model, Double value) {
        return new ScoreInfo(LEXICAL, model, value);
    }

    /** 只有专用重排服务给出的分数可以触发充分性门槛。 */
    public boolean isProviderModelScore() {
        return PROVIDER_MODEL.equals(origin) && value != null;
    }

    /** 重排服务或模型后备给出了分数；这类证据才算「有模型分数」。 */
    public boolean hasModelScore() {
        return value != null
                && (PROVIDER_MODEL.equals(origin) || LLM_FALLBACK.equals(origin) || LEXICAL.equals(origin));
    }
}
