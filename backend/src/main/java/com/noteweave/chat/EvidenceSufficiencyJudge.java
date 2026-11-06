package com.noteweave.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.EvidenceSufficiency;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.ScoreInfo;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 生成前判断已选证据是否足以回答。模型不可用时记 NOT_RUN，不允许改判为通过。
 * 模型给出的三态再与 rerankScore 组合，低分不能被抬成 SUFFICIENT。
 *
 * <p>提速：最高提供方重排分低于 0.15 时，无论模型怎么判，组合结果都是 INSUFFICIENT，
 * 这时直接按规则给结论；最高分达到 {@code rule-sufficient-at}（默认 0.80）时证据明显相关，
 * 也直接判 SUFFICIENT。只有中间分数段和没有提供方分数时才调用模型。</p>
 */
@Service
public class EvidenceSufficiencyJudge {
    private static final Logger log = LoggerFactory.getLogger(EvidenceSufficiencyJudge.class);
    static final String REASON_EMPTY = "EMPTY_EVIDENCE";
    static final String REASON_DISABLED = "EVIDENCE_SUFFICIENCY_DISABLED";
    static final String REASON_UNAVAILABLE = "EVIDENCE_SUFFICIENCY_LLM_UNAVAILABLE";
    static final String REASON_FAILED = "EVIDENCE_SUFFICIENCY_LLM_CALL_FAILED";
    static final String REASON_INVALID = "EVIDENCE_SUFFICIENCY_OUTPUT_INVALID";
    /** 本次证据里没有提供方重排分，只采用模型结论。 */
    static final String REASON_NO_PROVIDER_SCORE = "NO_PROVIDER_MODEL_SCORE";
    static final String REASON_BELOW_015 = "RERANK_SCORE_BELOW_0_15";
    static final String REASON_BELOW_035 = "RERANK_SCORE_BELOW_0_35";
    /** 最高提供方重排分很低，不调用模型直接判 INSUFFICIENT。 */
    static final String REASON_RULE_LOW = "RULE_RERANK_SCORE_BELOW_0_15";
    /** 最高提供方重排分很高，不调用模型直接判 SUFFICIENT。 */
    static final String REASON_RULE_HIGH = "RULE_RERANK_SCORE_HIGH";
    /** 分数落在中间区间或没有分数，且关闭了模型判定：不单独判定，由回答提示要求模型自行说明覆盖范围。 */
    static final String REASON_LLM_SKIPPED = "EVIDENCE_SUFFICIENCY_DEFERRED_TO_ANSWER";

    static final double PARTIAL_BELOW = 0.35d;
    static final double INSUFFICIENT_BELOW = 0.15d;
    private static final int OUTPUT_TOKENS = 200;
    /** 每条证据给判定模型看的命中段落长度上限。 */
    private static final int EXCERPT_LIMIT = 800;
    private static final Set<String> VERDICTS = Set.of("SUFFICIENT", "PARTIAL", "INSUFFICIENT");

    private static final String SYSTEM_PROMPT = """
            你是证据充分性判定器。只输出一个 JSON 对象，不要输出解释或 Markdown。
            字段 verdict 只能是 SUFFICIENT、PARTIAL、INSUFFICIENT。
            SUFFICIENT 表示已选资料足以直接回答问题。
            PARTIAL 表示资料只覆盖问题的一部分。
            INSUFFICIENT 表示资料不能支撑回答。
            用户的请求也可能是整理笔记、总结、列提纲、比较等任务：资料覆盖了任务涉及的主题时判 SUFFICIENT，
            只覆盖部分主题时判 PARTIAL，资料与主题无关时才判 INSUFFICIENT。
            不要使用资料之外的知识。
            """;

    private final ChatLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    /** 最高提供方重排分达到这个值时按规则判 SUFFICIENT；设成大于 1 的值即关闭这条规则。 */
    private final double ruleSufficientAt;
    /**
     * 规则给不出结论时是否调用模型判定。默认关闭：这次调用要在生成之前多等一次模型往返（实测 2 到 4 秒），
     * 而它的结论只决定提示里是否多一句覆盖不全的说明，这句要求可以直接交给回答模型。
     */
    private final boolean llmJudgeEnabled;

    public EvidenceSufficiencyJudge(
            ChatLlmClient llmClient,
            ObjectMapper objectMapper,
            boolean enabled
    ) {
        // 只用于单元测试：关闭高分规则，保留与模型结论等价的低分规则
        this(llmClient, objectMapper, enabled, 2.0d, true);
    }

    public EvidenceSufficiencyJudge(
            ChatLlmClient llmClient,
            ObjectMapper objectMapper,
            boolean enabled,
            double ruleSufficientAt
    ) {
        this(llmClient, objectMapper, enabled, ruleSufficientAt, true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EvidenceSufficiencyJudge(
            ChatLlmClient llmClient,
            ObjectMapper objectMapper,
            @Value("${noteweave.retrieval.evidence-sufficiency.enabled:true}") boolean enabled,
            @Value("${noteweave.retrieval.evidence-sufficiency.rule-sufficient-at:0.80}") double ruleSufficientAt,
            @Value("${noteweave.retrieval.evidence-sufficiency.llm-judge-enabled:false}") boolean llmJudgeEnabled
    ) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ruleSufficientAt = ruleSufficientAt;
        this.llmJudgeEnabled = llmJudgeEnabled;
    }

    public Judgement judge(String question, EvidenceBundle bundle) {
        if (bundle == null || bundle.evidence().isEmpty()) {
            return new Judgement(EvidenceSufficiency.NOT_RUN, List.of(REASON_EMPTY));
        }
        if (!enabled) {
            return new Judgement(EvidenceSufficiency.NOT_RUN, List.of(REASON_DISABLED));
        }
        if (llmClient == null || !llmClient.isEnabled()) {
            return new Judgement(EvidenceSufficiency.NOT_RUN, List.of(REASON_UNAVAILABLE));
        }
        // 规则只替代模型调用本身：模型不可用时仍按原口径记 NOT_RUN，不靠规则给出通过结论
        Judgement byRule = ruleJudgement(bundle.evidence(), ruleSufficientAt);
        if (byRule != null) {
            return byRule;
        }
        if (!llmJudgeEnabled) {
            return new Judgement(EvidenceSufficiency.NOT_RUN, List.of(REASON_LLM_SKIPPED));
        }
        String prompt = userPrompt(question, bundle);
        try {
            String first = llmClient.streamChat(SYSTEM_PROMPT, prompt, OUTPUT_TOKENS, token -> { });
            Parsed parsed = parse(first);
            if (parsed.verdict() == null) {
                String retryPrompt = prompt
                        + "\n\n上一次输出没有通过结构校验：" + parsed.error()
                        + "\n请只输出修正后的 JSON 对象。";
                String second = llmClient.streamChat(SYSTEM_PROMPT, retryPrompt, OUTPUT_TOKENS, token -> { });
                parsed = parse(second);
            }
            if (parsed.verdict() == null) {
                return new Judgement(EvidenceSufficiency.NOT_RUN, List.of(REASON_INVALID));
            }
            return combine(parsed.verdict(), bundle.evidence());
        } catch (RuntimeException ex) {
            log.warn("证据充分性判定调用失败：{}", ex.getMessage());
            return new Judgement(EvidenceSufficiency.NOT_RUN, List.of(REASON_FAILED));
        }
    }

    /**
     * 分数明显偏高或偏低时直接给结论，返回 null 表示需要模型判断。
     *
     * <p>低分规则与 {@link #combine} 的结果一致：分数低于 0.15 时模型的任何结论都会被合成为
     * INSUFFICIENT，调用模型只是多等几秒。高分规则是取舍：重排分达到阈值说明资料与问题高度相关，
     * 省掉一次模型调用；代价是本该判 PARTIAL 的少数情况会少一句只答覆盖部分的提示。</p>
     */
    static Judgement ruleJudgement(List<EvidenceBundle.Evidence> evidence, double sufficientAt) {
        Double score = modelScore(evidence);
        if (score == null) {
            return null;
        }
        if (score < INSUFFICIENT_BELOW) {
            return new Judgement(EvidenceSufficiency.INSUFFICIENT, List.of(REASON_RULE_LOW));
        }
        if (sufficientAt <= 1.0d && score >= sufficientAt) {
            return new Judgement(EvidenceSufficiency.SUFFICIENT, List.of(REASON_RULE_HIGH));
        }
        return null;
    }

    /**
     * 把模型结论和最高提供方重排分合成最终三态。
     *
     * <p>只有 {@link ScoreInfo#PROVIDER_MODEL} 的分数参与门槛。模型后备分、融合分、词法分和图扩展
     * 都不参与，避免把量纲不同的分数当成重排分来降级。没有提供方分数时只采用模型结论。</p>
     */
    static Judgement combine(EvidenceSufficiency modelVerdict, List<EvidenceBundle.Evidence> evidence) {
        if (modelVerdict == null || modelVerdict == EvidenceSufficiency.NOT_RUN) {
            return new Judgement(EvidenceSufficiency.NOT_RUN, List.of());
        }
        Double score = modelScore(evidence);
        if (score == null) {
            boolean unknownPolicy = evidence != null && evidence.stream().anyMatch(item ->
                    item != null && item.scoreInfo() != null && item.scoreInfo().isProviderModelScore());
            return new Judgement(modelVerdict, List.of(unknownPolicy
                    ? "PROVIDER_THRESHOLD_POLICY_NOT_APPLICABLE" : REASON_NO_PROVIDER_SCORE));
        }
        if (score < INSUFFICIENT_BELOW
                && (modelVerdict == EvidenceSufficiency.SUFFICIENT
                || modelVerdict == EvidenceSufficiency.PARTIAL)) {
            return new Judgement(EvidenceSufficiency.INSUFFICIENT, List.of(REASON_BELOW_015));
        }
        if (score < PARTIAL_BELOW && modelVerdict == EvidenceSufficiency.SUFFICIENT) {
            return new Judgement(EvidenceSufficiency.PARTIAL, List.of(REASON_BELOW_035));
        }
        return new Judgement(modelVerdict, List.of());
    }

    /** 本次证据里最高的提供方重排分；没有提供方分数时返回 null。 */
    static Double modelScore(List<EvidenceBundle.Evidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return null;
        }
        Double best = null;
        for (EvidenceBundle.Evidence item : evidence) {
            if (item == null || item.scoreInfo() == null || !item.scoreInfo().eligibleForThreshold()) {
                continue;
            }
            double value = item.scoreInfo().value();
            if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
                continue;
            }
            if (best == null || value > best) {
                best = value;
            }
        }
        return best;
    }

    private String userPrompt(String question, EvidenceBundle bundle) {
        StringBuilder builder = new StringBuilder();
        builder.append("问题：\n").append(question == null ? "" : question.trim()).append("\n\n已选资料：\n");
        int index = 1;
        for (EvidenceBundle.Evidence item : bundle.evidence()) {
            if (item == null) {
                continue;
            }
            builder.append(index++).append(". ").append(text(item.title())).append("\n");
            builder.append(trim(item.excerpt())).append("\n");
        }
        return builder.toString();
    }

    private Parsed parse(String output) {
        if (output == null || output.isBlank()) {
            return Parsed.invalid("输出为空");
        }
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Parsed.invalid("没有找到 JSON 对象");
        }
        try {
            JsonNode root = objectMapper.readTree(output.substring(start, end + 1));
            if (!root.isObject()) {
                return Parsed.invalid("根节点不是 JSON 对象");
            }
            JsonNode verdictNode = root.get("verdict");
            if (verdictNode == null || !verdictNode.isTextual()) {
                return Parsed.invalid("verdict 必须是字符串");
            }
            String verdict = verdictNode.asText().trim();
            if (!VERDICTS.contains(verdict)) {
                return Parsed.invalid("verdict 不在允许的取值内");
            }
            return new Parsed(EvidenceSufficiency.valueOf(verdict), "");
        } catch (Exception ex) {
            return Parsed.invalid("JSON 无法解析");
        }
    }

    private static String trim(String value) {
        return hitText(value);
    }

    /**
     * 证据正文已经是命中块自己的正文，不再需要剥离相邻段落。
     * 保留这个入口是为了让调用方与既有测试继续按同一份正文工作。
     */
    static String hitText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    public record Judgement(EvidenceSufficiency verdict, List<String> reasons) {
        /** 充分性留给回答模型自行判断时，提示里要加上覆盖范围的要求。 */
        public boolean deferredToAnswer() {
            return reasons != null && reasons.contains(REASON_LLM_SKIPPED);
        }

        public Judgement {
            verdict = verdict == null ? EvidenceSufficiency.NOT_RUN : verdict;
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    private record Parsed(EvidenceSufficiency verdict, String error) {
        private static Parsed invalid(String error) {
            return new Parsed(null, error);
        }
    }
}
