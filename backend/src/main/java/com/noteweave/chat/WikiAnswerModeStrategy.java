package com.noteweave.chat;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.AnswerModeStrategy;
import com.noteweave.answer.strategy.AnswerPolicy;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.PromptSpec;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.knowledge.KnowledgeCitationResponse;
import com.noteweave.knowledge.KnowledgePageHit;
import com.noteweave.knowledge.WikiPageContext;
import com.noteweave.knowledge.WikiLinkResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class WikiAnswerModeStrategy implements AnswerModeStrategy {

    public static final String PLAN_VERSION = "wiki-page-graph-v1";
    public static final String ATTRIBUTE_CONTEXT_APPLIED = "context_applied";
    public static final String ATTRIBUTE_WINDOW_TURN_COUNT = "window_turn_count";
    public static final String ATTRIBUTE_TOPIC_ANCHOR = "topic_anchor";
    public static final String ATTRIBUTE_TOPIC_SUMMARY = "topic_summary";
    public static final String ATTRIBUTE_HAS_CHAT_CONTROLS = "has_chat_controls";
    public static final String ATTRIBUTE_CHAT_CONTROL_SECTION = "chat_control_section";
    public static final String ATTRIBUTE_TEMPLATE_LABEL = "template_label";

    private final WikiRetrievalSnapshotCodec snapshotCodec;

    public WikiAnswerModeStrategy(WikiRetrievalSnapshotCodec snapshotCodec) {
        this.snapshotCodec = snapshotCodec;
    }

    @Override
    public AnswerMode supports() {
        return AnswerMode.WIKI;
    }

    @Override
    public RetrievalPlan plan(AnswerContext context) {
        return new RetrievalPlan(
                PLAN_VERSION,
                AnswerMode.WIKI,
                List.of(new RetrievalPlan.Step(
                        WikiEvidenceRetriever.CHANNEL,
                        5,
                        1.0,
                        Map.of("workspace_id", context.workspaceId(), "item_status", "ACTIVE")
                )),
                new RetrievalPlan.Budget(5, 20_000, 1, 30, 60, 4_000)
        );
    }

    @Override
    public PromptSpec compose(AnswerContext context, EvidenceBundle bundle) {
        String encodedSnapshot = bundle.metadata().get(WikiRetrievalSnapshotCodec.METADATA_KEY);
        WikiRetrievalSnapshot snapshot = encodedSnapshot == null || encodedSnapshot.isBlank()
                ? new WikiRetrievalSnapshot(List.of(), List.of())
                : snapshotCodec.decode(encodedSnapshot);
        Set<String> selectedVersionIds = bundle.evidence().stream()
                .filter(evidence -> "KNOWLEDGE_VERSION".equals(evidence.kind()))
                .map(EvidenceBundle.Evidence::knowledgeVersionId)
                .collect(java.util.stream.Collectors.toSet());
        List<WikiPageContext> selectedContexts = snapshot.contexts().stream()
                .filter(contextItem -> selectedVersionIds.contains(contextItem.page().versionId()))
                .toList();
        if (selectedContexts.isEmpty()) {
            return new PromptSpec(
                    "Answer only from formal workspace Wiki pages.",
                    renderFallback(context),
                    List.of(),
                    PLAN_VERSION,
                    true
            );
        }
        return new PromptSpec(
                "Answer from the selected Knowledge Versions and preserve their source provenance.",
                renderAnswer(context, selectedContexts),
                bundle.evidence().stream().map(EvidenceBundle.Evidence::evidenceId).toList(),
                PLAN_VERSION
        );
    }

    @Override
    public AnswerPolicy policy() {
        return new AnswerPolicy(1, false, true, 1_800);
    }

    private String renderFallback(AnswerContext context) {
        StringBuilder fallback = new StringBuilder();
        fallback.append("## 基于全量 Wiki 的回答\n");
        fallback.append("当前 Wiki 知识网络还没有可直接命中的正式页面，因此本次不退回普通资料 RAG 直接作答。\n\n");
        fallback.append("## 建议动作\n");
        fallback.append("- 如果这是一个需要长期维护的主题，先开启工作台级 Wiki 构建，并让现有资料进入 Wiki ingest。\n");
        fallback.append("- 如果要立刻补齐某个概念页，可以在默认 Wiki 工作台中手动补充或修正页面正文。\n");
        fallback.append("- 或先用 Note 链路通过资料级检索读取原文窗口，再把稳定结论沉淀进工作台 Wiki 网络。\n");
        fallback.append(context.attributes().getOrDefault(ATTRIBUTE_CHAT_CONTROL_SECTION, ""));
        fallback.append("\n## 默认 Wiki 工作台\n");
        fallback.append("/workspaces/").append(context.workspaceId()).append("/wiki\n");
        return fallback.toString();
    }

    private String renderAnswer(AnswerContext context, List<WikiPageContext> contexts) {
        StringBuilder builder = new StringBuilder();
        builder.append("## 基于全量 Wiki 的回答\n");
        builder.append("我优先检索当前工作台已经沉淀的 Wiki Index、页面正文、页面链接、反向链接和来源回链，再基于多页面知识网络综合回答，而不是退回普通资料 chunk 问答。\n\n");
        if (contextApplied(context)) {
            builder.append("本轮已结合最近连续对话窗口理解这次追问。");
            String topicAnchor = context.attributes().getOrDefault(ATTRIBUTE_TOPIC_ANCHOR, "");
            if (!topicAnchor.isBlank()) {
                builder.append(" 当前主题锚点是“").append(topicAnchor).append("”。");
            }
            builder.append("\n\n");
        }
        if (Boolean.parseBoolean(context.attributes().getOrDefault(ATTRIBUTE_HAS_CHAT_CONTROLS, "false"))) {
            builder.append("本轮还应用了工作台级 Chat Control Pack。\n\n");
        }
        builder.append("## 相关 Wiki 页面\n");
        for (WikiPageContext pageContext : contexts) {
            KnowledgePageHit page = pageContext.page();
            builder.append("- 《").append(page.title()).append("》v").append(page.versionNo())
                    .append("：").append(trim(page.summary().isBlank() ? page.content() : page.summary(), 180))
                    .append("，出链 ").append(pageContext.outgoingLinks().size())
                    .append("，反链 ").append(pageContext.backlinks().size())
                    .append("，来源 ").append(pageContext.citations().size())
                    .append("\n");
        }
        builder.append("\n## 综合结论\n");
        builder.append(buildWikiSynthesis(contexts)).append("\n\n");
        builder.append("## 关键页面关系\n");
        appendWikiLinks(builder, collectNetworkLinks(contexts, true), "当前命中的 Wiki 页面之间还没有足够显式的页面关系。");
        builder.append("\n## 反向引用关系\n");
        appendWikiLinks(builder, collectNetworkLinks(contexts, false), "当前命中的 Wiki 页面暂时没有明显反向引用网络。");
        builder.append("\n## 来源回链\n");
        List<KnowledgeCitationResponse> citations = collectNetworkCitations(contexts);
        if (citations.isEmpty()) {
            builder.append("当前命中页面暂时没有绑定来源引用。\n");
        } else {
            for (KnowledgeCitationResponse citation : citations) {
                builder.append("- ").append(formatKnowledgeCitationTitle(citation))
                        .append("：").append(trim(citation.quoteText(), 120))
                        .append("（").append(citation.locationInfo()).append("）\n");
            }
        }
        builder.append("\n## 页面关系\n");
        builder.append("相关页面关系、图谱、待处理任务、问题分层和治理动作都可以在默认 Wiki 工作台中继续查看。\n\n");
        if (contextApplied(context)) {
            builder.append("## 会话上下文\n");
            builder.append("- 连续对话窗口：最近 ")
                    .append(context.attributes().getOrDefault(ATTRIBUTE_WINDOW_TURN_COUNT, "0"))
                    .append(" 轮相关对话\n");
            String topicAnchor = context.attributes().getOrDefault(ATTRIBUTE_TOPIC_ANCHOR, "");
            if (!topicAnchor.isBlank()) {
                builder.append("- 主题锚点：").append(topicAnchor).append("\n");
            }
            String topicSummary = context.attributes().getOrDefault(ATTRIBUTE_TOPIC_SUMMARY, "");
            if (!topicSummary.isBlank()) {
                builder.append("- 前序主题摘要：").append(topicSummary).append("\n");
            }
            builder.append("\n");
        }
        builder.append(context.attributes().getOrDefault(ATTRIBUTE_CHAT_CONTROL_SECTION, ""));
        builder.append("## 默认 Wiki 工作台\n");
        builder.append("/workspaces/").append(context.workspaceId()).append("/wiki\n");
        return builder.toString();
    }

    private void appendWikiLinks(StringBuilder builder, List<WikiLinkResponse> links, String emptyMessage) {
        if (links.isEmpty()) {
            builder.append(emptyMessage).append("\n");
            return;
        }
        for (WikiLinkResponse link : links) {
            builder.append("- ").append(link.targetTitle())
                    .append("：").append(link.relationStatus())
                    .append("，").append(link.mentionCount()).append(" 次提及\n");
        }
    }

    private String buildWikiSynthesis(List<WikiPageContext> contexts) {
        List<String> facts = new ArrayList<>();
        for (WikiPageContext context : contexts.stream().limit(3).toList()) {
            KnowledgePageHit page = context.page();
            String snippet = trim(page.summary().isBlank() ? page.content() : page.summary(), 150);
            facts.add("《" + page.title() + "》指出：" + snippet);
        }
        if (facts.isEmpty()) {
            return "当前没有足够的 Wiki 页面可综合。";
        }
        return String.join("\n", facts);
    }

    private List<WikiLinkResponse> collectNetworkLinks(List<WikiPageContext> contexts, boolean outgoing) {
        Map<String, WikiLinkResponse> dedup = new LinkedHashMap<>();
        for (WikiPageContext context : contexts.stream().limit(3).toList()) {
            List<WikiLinkResponse> links = outgoing ? context.outgoingLinks() : context.backlinks();
            for (WikiLinkResponse link : links) {
                String key = (link.targetItemId() == null ? "" : link.targetItemId())
                        + "|" + link.targetTitle() + "|" + link.relationStatus();
                dedup.putIfAbsent(key, link);
                if (dedup.size() >= 6) {
                    return new ArrayList<>(dedup.values());
                }
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private List<KnowledgeCitationResponse> collectNetworkCitations(List<WikiPageContext> contexts) {
        Map<String, KnowledgeCitationResponse> dedup = new LinkedHashMap<>();
        for (WikiPageContext context : contexts.stream().limit(3).toList()) {
            for (KnowledgeCitationResponse citation : context.citations()) {
                String key = citation.sourceId() + "|" + citation.title() + "|" + citation.locationInfo();
                dedup.putIfAbsent(key, citation);
                if (dedup.size() >= 6) {
                    return new ArrayList<>(dedup.values());
                }
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private String formatKnowledgeCitationTitle(KnowledgeCitationResponse citation) {
        if ("research_agent".equals(citation.generatedBy())) {
            return citation.title() + " · Research Report(" + (
                    citation.generatedRefId() == null || citation.generatedRefId().isBlank()
                            ? "unknown run" : citation.generatedRefId()) + ")";
        }
        return citation.title();
    }

    private boolean contextApplied(AnswerContext context) {
        return Boolean.parseBoolean(context.attributes().getOrDefault(ATTRIBUTE_CONTEXT_APPLIED, "false"));
    }

    private String trim(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max - 1) + "...";
    }
}
