package com.noteweave.chat;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.AnswerModeStrategy;
import com.noteweave.answer.strategy.AnswerPolicy;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.PromptSpec;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class QaAnswerModeStrategy implements AnswerModeStrategy {

    public static final String V2_PLAN_VERSION = "qa-weknora-hybrid-v1";
    /** All new QA AnswerRuns use this single V2 plan version. */
    public static final String PLAN_VERSION = V2_PLAN_VERSION;
    public static final String ATTRIBUTE_QUESTION_TYPE = "question_type";
    public static final String ATTRIBUTE_CONTEXT_APPLIED = "context_applied";
    public static final String ATTRIBUTE_WINDOW_TURN_COUNT = "window_turn_count";
    public static final String ATTRIBUTE_TOPIC_ANCHOR = "topic_anchor";
    public static final String ATTRIBUTE_TOPIC_SUMMARY = "topic_summary";
    public static final String ATTRIBUTE_HAS_CHAT_CONTROLS = "has_chat_controls";
    public static final String ATTRIBUTE_CHAT_CONTROL_SECTION = "chat_control_section";
    public static final String ATTRIBUTE_TEMPLATE_LABEL = "template_label";

    @Override
    public AnswerMode supports() {
        return AnswerMode.QA;
    }

    @Override
    public RetrievalPlan plan(AnswerContext context) {
        QaRetrievalStrategyProfile profile = QaRetrievalStrategyProfile.V2;
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("workspace_id", context.workspaceId());
        filters.put("snapshot_status", "ACTIVE");
        filters.put(QaRetrievalStrategyProfile.FILTER_PROFILE, profile.profileVersion());
        filters.put(QaRetrievalStrategyProfile.FILTER_RELEVANCE_POLICY,
                profile.relevancePolicyVersion());
        filters.put(QaRetrievalStrategyProfile.FILTER_SELECTION_POLICY,
                profile.selectionPolicyVersion());
        filters.put(QaRetrievalStrategyProfile.FILTER_V2_ENABLED,
                Boolean.toString(profile.v2Enabled()));
        filters.put(QaRetrievalStrategyProfile.FILTER_QUERY_REWRITE_VERSION,
                QaRetrievalStrategyProfile.QUERY_REWRITE_VERSION);
        filters.put(QaRetrievalStrategyProfile.FILTER_QUERY_EXPANSION_POLICY,
                QaRetrievalStrategyProfile.QUERY_EXPANSION_POLICY);
        filters.put(QaRetrievalStrategyProfile.FILTER_ORIGINAL_QUERY,
                context.attributes().getOrDefault("request_content", context.query()));
        filters.put(QaRetrievalStrategyProfile.FILTER_RETRIEVAL_QUERIES, context.query());
        filters.put(QaRetrievalStrategyProfile.FILTER_MUST_TERMS,
                context.attributes().getOrDefault("topic_anchor", ""));
        filters.put(QaRetrievalStrategyProfile.FILTER_PREFERRED_TERMS,
                preferredTerms(context.attributes().getOrDefault("request_content", context.query())));
        filters.put(QaRetrievalStrategyProfile.FILTER_LANGUAGE, language(context.query()));
        filters.put(QaRetrievalStrategyProfile.FILTER_QUESTION_TYPE,
                context.attributes().getOrDefault("question_type", "definition"));
        filters.put(QaRetrievalStrategyProfile.FILTER_VECTOR_OVER_RECALL,
                Integer.toString(QaRetrievalStrategyProfile.RECALL_LIMIT));
        filters.put(QaRetrievalStrategyProfile.FILTER_KEYWORD_OVER_RECALL,
                Integer.toString(QaRetrievalStrategyProfile.RECALL_LIMIT));
        filters.put(QaRetrievalStrategyProfile.FILTER_FUSION_LIMIT,
                Integer.toString(QaRetrievalStrategyProfile.FUSION_LIMIT));
        filters.put(QaRetrievalStrategyProfile.FILTER_RERANK_TOP_N,
                Integer.toString(QaRetrievalStrategyProfile.RERANK_LIMIT));
        filters.put(QaRetrievalStrategyProfile.FILTER_RRF_K,
                Integer.toString(QaRetrievalStrategyProfile.RRF_K));
        filters.put(QaRetrievalStrategyProfile.FILTER_VECTOR_WEIGHT,
                Double.toString(QaRetrievalStrategyProfile.VECTOR_WEIGHT));
        filters.put(QaRetrievalStrategyProfile.FILTER_KEYWORD_WEIGHT,
                Double.toString(QaRetrievalStrategyProfile.KEYWORD_WEIGHT));
        filters.put(QaRetrievalStrategyProfile.FILTER_VECTOR_THRESHOLD,
                Double.toString(QaRetrievalStrategyProfile.VECTOR_THRESHOLD));
        filters.put(QaRetrievalStrategyProfile.FILTER_KEYWORD_THRESHOLD,
                Double.toString(QaRetrievalStrategyProfile.KEYWORD_THRESHOLD));
        if (!context.sourceScope().isEmpty()) {
            filters.put("source_ids", String.join(",", context.sourceScope().stream().sorted().toList()));
        }
        return new RetrievalPlan(
                profile.planVersion(),
                AnswerMode.QA,
                List.of(new RetrievalPlan.Step(
                        QaPassageEvidenceRetriever.CHANNEL,
                        QaRetrievalStrategyProfile.CANDIDATE_LIMIT,
                        QaRetrievalStrategyProfile.STEP_WEIGHT,
                        filters
                )),
                new RetrievalPlan.Budget(
                        profile.maxEvidence(),
                        profile.maxEvidenceCharacters(),
                        0,
                        0)
        );
    }

    private String preferredTerms(String query) {
        if (query == null || query.isBlank()) return "";
        return java.util.Arrays.stream(query.trim().split("[^\\p{IsHan}A-Za-z0-9+_./-]+"))
                .map(String::trim).filter(term -> term.length() >= 2).distinct().limit(12)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private String language(String query) {
        if (query != null && query.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) {
            return "zh";
        }
        return "en";
    }

    @Override
    public PromptSpec compose(AnswerContext context, EvidenceBundle bundle) {
        if (bundle.evidence().isEmpty()) {
            return new PromptSpec(
                    "Answer only from selected workspace evidence.",
                    "当前工作台资料中暂未检索到足够依据，可先上传相关资料后再提问。",
                    List.of(),
                    bundle.retrievalPlanVersion(),
                    true
            );
        }
        String questionType = context.attributes().getOrDefault(ATTRIBUTE_QUESTION_TYPE, "general");
        boolean contextApplied = Boolean.parseBoolean(
                context.attributes().getOrDefault(ATTRIBUTE_CONTEXT_APPLIED, "false"));
        boolean hasChatControls = Boolean.parseBoolean(
                context.attributes().getOrDefault(ATTRIBUTE_HAS_CHAT_CONTROLS, "false"));
        StringBuilder answer = new StringBuilder();
        answer.append("请直接回答用户的问题，并将下列资料片段综合成自然、清晰的中文。\n\n")
                .append("回答要求：\n")
                .append("- 先给结论，再补充必要解释；不要复述这些指令。\n")
                .append("- 只使用提供的资料，不补写资料中没有的事实。\n")
                .append("- 不要提及检索算法、向量、BM25、RRF、rerank、score、chunk、reason、证据 ID 或内部执行过程。\n")
                .append("- 不要在正文重复生成来源清单，来源与原文摘录会由界面的引用卡片单独展示。\n")
                .append("- 资料不足时，明确说明缺少哪一类信息。\n");
        if (contextApplied) {
            answer.append("- 将最近连续对话仅用于理解追问语境，不要向用户描述上下文窗口。\n");
        }
        if (hasChatControls) {
            answer.append("- 遵循下方表达控制，但不要提及 Chat Control Pack。\n");
        }
        if ("comparison".equals(questionType)) {
            answer.append("- 这是比较类问题，请按相同维度比较不同对象。\n");
        }
        answer.append("\n内部检索边界：").append(retrievalBoundary(context)).append("。不要在回答中提及。\n")
                .append("\n可用资料片段：\n");
        for (int index = 0; index < bundle.evidence().size(); index++) {
            EvidenceBundle.Evidence item = bundle.evidence().get(index);
            answer.append("- 《").append(item.title()).append("》：")
                    .append(trim(item.excerpt(), 900)).append("\n");
        }
        answer.append("\n")
                .append(context.attributes().getOrDefault(ATTRIBUTE_CHAT_CONTROL_SECTION, ""));
        return new PromptSpec(
                "Answer only from the provided EvidenceBundle and cite its evidence ids.",
                answer.toString(),
                bundle.evidence().stream().map(EvidenceBundle.Evidence::evidenceId).toList(),
                bundle.retrievalPlanVersion()
        );
    }

    @Override
    public AnswerPolicy policy() {
        return new AnswerPolicy(1, false, true, 1_200);
    }

    private String trim(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max - 1) + "...";
    }

    private String retrievalBoundary(AnswerContext context) {
        if (context.sourceScope().isEmpty()) {
            return "当前 workspace 内已解析资料";
        }
        return "用户显式选择的 " + context.sourceScope().size() + " 份资料";
    }
}
