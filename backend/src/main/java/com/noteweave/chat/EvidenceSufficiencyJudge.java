package com.noteweave.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.EvidenceSufficiency;
import com.noteweave.answer.strategy.EvidenceBundle;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 生成前判断已选证据是否足以回答。模型不可用时记 NOT_RUN，不允许改判为通过。
 * 模型给出的三态再与 rerankScore 组合，低分不能被抬成 SUFFICIENT。
 */
@Service
public class EvidenceSufficiencyJudge {
    private static final Logger log = LoggerFactory.getLogger(EvidenceSufficiencyJudge.class);
    static final String REASON_EMPTY = "EMPTY_EVIDENCE";
    static final String REASON_DISABLED = "EVIDENCE_SUFFICIENCY_DISABLED";
    static final String REASON_UNAVAILABLE = "EVIDENCE_SUFFICIENCY_LLM_UNAVAILABLE";
    static final String REASON_FAILED = "EVIDENCE_SUFFICIENCY_LLM_CALL_FAILED";
    static final String REASON_INVALID = "EVIDENCE_SUFFICIENCY_OUTPUT_INVALID";
    static final String REASON_ABSENT = "RERANK_SCORE_ABSENT";
    static final String REASON_BELOW_015 = "RERANK_SCORE_BELOW_0_15";
    static final String REASON_BELOW_035 = "RERANK_SCORE_BELOW_0_35";

    /** 两路 RRF 上限大约 0.033。低于这个值的不是模型 Rerank 分。 */
    static final double MODEL_SCORE_FLOOR = 0.05d;
    static final double PARTIAL_BELOW = 0.35d;
    static final double INSUFFICIENT_BELOW = 0.15d;
    private static final double WIKI_SCORE_SCALE = 100.0d;
    private static final int OUTPUT_TOKENS = 200;
    private static final int EXCERPT_LIMIT = 400;
    private static final Set<String> VERDICTS = Set.of("SUFFICIENT", "PARTIAL", "INSUFFICIENT");

    private static final String SYSTEM_PROMPT = """
            你是证据充分性判定器。只输出一个 JSON 对象，不要输出解释或 Markdown。
            字段 verdict 只能是 SUFFICIENT、PARTIAL、INSUFFICIENT。
            SUFFICIENT 表示已选资料足以直接回答问题。
            PARTIAL 表示资料只覆盖问题的一部分。
            INSUFFICIENT 表示资料不能支撑回答。
            不要使用资料之外的知识。
            """;

    private final ChatLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public EvidenceSufficiencyJudge(
            ChatLlmClient llmClient,
            ObjectMapper objectMapper,
            @Value("${noteweave.retrieval.evidence-sufficiency.enabled:true}") boolean enabled
    ) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
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
     * 把模型结论和最高 rerankScore 合成最终三态。
     * Wiki 种子页的分数是百分制，其余链路成功时是 0 到 1。扩展页不参与。
     */
    static Judgement combine(EvidenceSufficiency modelVerdict, List<EvidenceBundle.Evidence> evidence) {
        if (modelVerdict == null || modelVerdict == EvidenceSufficiency.NOT_RUN) {
            return new Judgement(EvidenceSufficiency.NOT_RUN, List.of());
        }
        Double score = modelScore(evidence);
        if (score == null) {
            return new Judgement(modelVerdict, List.of(REASON_ABSENT));
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

    private static Double modelScore(List<EvidenceBundle.Evidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return null;
        }
        EvidenceBundle.Evidence best = null;
        for (EvidenceBundle.Evidence item : evidence) {
            if (item == null || hasGraphPath(item)) {
                continue;
            }
            double raw = item.rerankScore();
            if (!Double.isFinite(raw) || raw <= 0.0d) {
                continue;
            }
            if (best == null || raw > best.rerankScore()) {
                best = item;
            }
        }
        if (best == null) {
            return null;
        }
        double normalized = "KNOWLEDGE_VERSION".equals(best.kind())
                ? best.rerankScore() / WIKI_SCORE_SCALE
                : best.rerankScore();
        if (!Double.isFinite(normalized) || normalized < MODEL_SCORE_FLOOR || normalized > 1.0d) {
            return null;
        }
        return normalized;
    }

    private static boolean hasGraphPath(EvidenceBundle.Evidence evidence) {
        String path = evidence.metadata().get(WikiEvidenceRetriever.GRAPH_PATH_METADATA);
        return path != null && !path.isBlank();
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
        String text = value == null ? "" : value.trim();
        if (text.length() <= EXCERPT_LIMIT) {
            return text;
        }
        return text.substring(0, EXCERPT_LIMIT);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    public record Judgement(EvidenceSufficiency verdict, List<String> reasons) {
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
