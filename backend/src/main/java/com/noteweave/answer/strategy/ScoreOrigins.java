package com.noteweave.answer.strategy;

import com.noteweave.retrieval.provider.RerankClient;
import java.util.List;

/**
 * 把重排结果折算成 {@link ScoreInfo}。
 *
 * <p>重排是否真的跑过，决定了分数能不能用于充分性门槛。三条链路的降级原因里都会带上
 * {@code *_RERANK_UNAVAILABLE} 后缀，这里统一按这个后缀判断，调用方不再各自写一遍。</p>
 */
public final class ScoreOrigins {

    static final String RERANK_UNAVAILABLE_SUFFIX = "RERANK_UNAVAILABLE";

    private ScoreOrigins() {
    }

    /** 重排整体没有跑成：分数来自融合阶段。 */
    public static boolean rerankUnavailable(List<String> degradationReasons) {
        if (degradationReasons == null || degradationReasons.isEmpty()) {
            return false;
        }
        for (String reason : degradationReasons) {
            if (reason != null && reason.endsWith(RERANK_UNAVAILABLE_SUFFIX)) {
                return true;
            }
        }
        return false;
    }

    /** 按重排返回的模型名区分提供方分数与模型后备分数。 */
    public static ScoreInfo fromRerankModel(String model, double value) {
        if (model == null || model.isBlank()) {
            return ScoreInfo.unknown();
        }
        return RerankClient.LLM_FALLBACK_MODEL.equals(model)
                ? ScoreInfo.llmFallback(model, value)
                : ScoreInfo.provider(model, value);
    }

    /** 重排覆盖不到的尾部候选：分数是融合分补的。 */
    public static ScoreInfo fusionFallback() {
        return ScoreInfo.fusion();
    }
}
