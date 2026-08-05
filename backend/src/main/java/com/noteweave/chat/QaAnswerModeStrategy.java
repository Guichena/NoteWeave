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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        Set<String> sourceTitles = new LinkedHashSet<>();
        bundle.evidence().forEach(item -> sourceTitles.add(item.title()));
        String questionType = context.attributes().getOrDefault(ATTRIBUTE_QUESTION_TYPE, "general");
        boolean contextApplied = Boolean.parseBoolean(
                context.attributes().getOrDefault(ATTRIBUTE_CONTEXT_APPLIED, "false"));
        boolean hasChatControls = Boolean.parseBoolean(
                context.attributes().getOrDefault(ATTRIBUTE_HAS_CHAT_CONTROLS, "false"));
        StringBuilder answer = new StringBuilder();
        EvidenceBundle.Evidence primaryEvidence = bundle.evidence().get(0);
        answer.append("## 直接回答\n")
                .append(trim(primaryEvidence.excerpt(), 280));
        if (bundle.evidence().size() > 1) {
            answer.append("\n\n补充依据：").append(trim(bundle.evidence().get(1).excerpt(), 160));
        }
        if (contextApplied) {
            answer.append("本轮已结合最近连续对话窗口理解这次追问。");
        }
        if (hasChatControls) {
            answer.append("本轮还应用了工作台级 Chat Control Pack。");
        }
        if ("comparison".equals(questionType)) {
            answer.append("这个问题属于比较类问题，因此证据选择会优先覆盖不同资料来源，避免只引用同一份资料的多个片段。");
        }
        answer.append("\n\n")
                .append("## 证据选择\n")
                .append("- 查询意图：").append(questionType).append("\n");
        if (contextApplied) {
            answer.append("- 会话上下文：已纳入最近连续对话窗口\n")
                    .append("- 连续对话窗口：最近 ")
                    .append(context.attributes().getOrDefault(ATTRIBUTE_WINDOW_TURN_COUNT, "0"))
                    .append(" 轮相关对话\n");
            String topicAnchor = context.attributes().getOrDefault(ATTRIBUTE_TOPIC_ANCHOR, "");
            if (!topicAnchor.isBlank()) {
                answer.append("- 主题锚点：").append(topicAnchor).append("\n");
            }
            String topicSummary = context.attributes().getOrDefault(ATTRIBUTE_TOPIC_SUMMARY, "");
            if (!topicSummary.isBlank()) {
                answer.append("- 前序主题摘要：").append(topicSummary).append("\n");
            }
        }
        answer.append("- 检索边界：").append(retrievalBoundary(context)).append("\n")
                .append("- 检索策略：Chunk 向量召回 + BM25 关键词召回 + 加权 RRF + 真实 rerank + 来源覆盖\n")
                .append("- 来源覆盖：").append(sourceTitles.size()).append(" 个资料来源");
        if (!sourceTitles.isEmpty()) {
            answer.append("（").append(String.join("、", sourceTitles)).append("）");
        }
        answer.append("\n\n")
                .append("## 关键依据\n");
        for (int index = 0; index < bundle.evidence().size(); index++) {
            EvidenceBundle.Evidence item = bundle.evidence().get(index);
            answer.append("- 证据 ").append(index + 1)
                    .append("《").append(item.title()).append("》")
                    .append("：").append(trim(item.excerpt(), 220))
                    .append("（").append(item.location())
                    .append("，score=").append(formatScore(item.rawScore()))
                    .append("，reason=").append(item.selectionReason()).append("）\n");
        }
        answer.append("\n## 引用来源\n")
                .append("本轮回答的事实依据全部来自当前轮选中的资料片段，引用信息会通过 `citation.upsert` 事件返回，可回跳到 source / snapshot / chunk。\n\n")
                .append(context.attributes().getOrDefault(ATTRIBUTE_CHAT_CONTROL_SECTION, ""))
                .append("## 可继续操作\n")
                .append("- 如果需要逐篇深读和摘录卡片，可以切换到 Note 链路。\n")
                .append("- 如果问题依赖长期页面网络，可以切换到 Wiki 链路。\n");
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

    private String formatScore(double score) {
        if (score == Math.rint(score)) {
            return Long.toString(Math.round(score));
        }
        return Double.toString(score);
    }

    private String retrievalBoundary(AnswerContext context) {
        if (context.sourceScope().isEmpty()) {
            return "当前 workspace 内已解析资料";
        }
        return "用户显式选择的 " + context.sourceScope().size() + " 份资料";
    }
}
