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
import com.noteweave.knowledge.WikiPersonalizedPageRank;
import com.noteweave.knowledge.WikiLinkResponse;
import com.noteweave.retrieval.QueryRewrite;
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
    /** 关系与来源回链去重后最多保留的条数。 */
    private static final int NETWORK_DEDUP_LIMIT = 6;

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
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("workspace_id", context.workspaceId());
        filters.put("item_status", "ACTIVE");
        QueryRewrite.copyInto(filters, context.attributes());
        return new RetrievalPlan(
                PLAN_VERSION,
                AnswerMode.WIKI,
                List.of(new RetrievalPlan.Step(
                        WikiEvidenceRetriever.CHANNEL,
                        5,
                        1.0,
                        filters
                )),
                // 检索候选 5 条，图扩展最多 30 个节点。证据上限取两者之和，扩展页才不会被种子占满后丢掉。
                new RetrievalPlan.Budget(5 + 30, 20_000, WikiPersonalizedPageRank.MAX_HOPS, 30, 60, 4_000)
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
        Map<String, List<String>> paths = new LinkedHashMap<>();
        for (EvidenceBundle.Evidence evidence : bundle.evidence()) {
            List<String> path = snapshotCodec.decodeGraphPath(
                    evidence.metadata().get(WikiEvidenceRetriever.GRAPH_PATH_METADATA));
            if (!path.isEmpty() && evidence.knowledgeVersionId() != null) {
                paths.put(evidence.knowledgeVersionId(), path);
            }
        }
        return new PromptSpec(
                "Answer from the selected Knowledge Versions and preserve their source provenance.",
                renderAnswer(context, selectedContexts, paths),
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

    /**
     * Wiki 回答的生成提示：用户问题、命中页面的正文和页面之间的关系。
     *
     * <p>原来的草稿只有固定的过程说明、页面清单和拼出来的综合结论，没有用户问题，模型只能润色这段文字，
     * 回答常常答非所问。现在把问题和页面正文交给模型，先直接回答，再用 [[页面名]] 标注依据的页面。</p>
     */
    private String renderAnswer(
            AnswerContext context,
            List<WikiPageContext> contexts,
            Map<String, List<String>> paths
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户问题：").append(context.attributes().getOrDefault(
                NoteAnswerModeStrategy.ATTRIBUTE_REQUEST_CONTENT, context.query())).append("\n");
        if (!context.query().equals(context.attributes().getOrDefault(
                NoteAnswerModeStrategy.ATTRIBUTE_REQUEST_CONTENT, context.query()))) {
            builder.append("结合上文理解后的完整问题：").append(context.query()).append("\n");
        }
        builder.append("\n请根据下面工作台 Wiki 中的页面回答这个问题，写成自然、清晰的中文。\n\n")
                .append("回答要求：\n")
                .append("- 第一段用一两句话直接回答问题，再按问题的结构展开；需要综合多个页面时，说明它们之间的联系。\n")
                .append("- 只使用下面页面中的内容，不补写页面里没有的事实；与问题无关的页面内容不要写进来。\n")
                .append("- 依据某个页面的句子末尾用 [[页面名]] 标注页面，页面名必须与下面给出的完全一致。\n")
                .append("- 不要描述你如何检索或组织这次回答，不要列出页面版本、链接数量等统计，不要在末尾另列来源清单。\n")
                .append("- 页面只覆盖问题的一部分时，先回答能回答的部分，再说明还缺哪方面的信息。\n\n");
        ConversationWindow.append(builder, context);
        builder.append("Wiki 页面：\n");
        for (WikiPageContext pageContext : contexts) {
            KnowledgePageHit page = pageContext.page();
            builder.append("[[").append(page.title()).append("]]\n");
            if (!page.summary().isBlank()) {
                builder.append("摘要：").append(trim(page.summary(), 300)).append("\n");
            }
            builder.append("正文：").append(trim(page.content(), 1_500)).append("\n");
            List<String> related = pageContext.outgoingLinks().stream().map(WikiLinkResponse::targetTitle)
                    .filter(title -> title != null && !title.isBlank()).distinct().limit(8).toList();
            if (!related.isEmpty()) {
                builder.append("链接到的页面：").append(String.join("、", related)).append("\n");
            }
            List<String> referring = pageContext.backlinks().stream().map(WikiLinkResponse::targetTitle)
                    .filter(title -> title != null && !title.isBlank()).distinct().limit(8).toList();
            if (!referring.isEmpty()) {
                builder.append("被这些页面引用：").append(String.join("、", referring)).append("\n");
            }
            List<String> path = paths.get(page.versionId());
            if (path != null && path.size() > 1) {
                builder.append("与问题相关页面的关联路径：").append(String.join(" → ", path)).append("\n");
            }
            builder.append("\n");
        }
        builder.append(context.attributes().getOrDefault(ATTRIBUTE_CHAT_CONTROL_SECTION, ""));
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
        for (WikiPageContext context : contexts) {
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
        for (WikiPageContext context : contexts) {
            List<WikiLinkResponse> links = outgoing ? context.outgoingLinks() : context.backlinks();
            for (WikiLinkResponse link : links) {
                String key = (link.targetItemId() == null ? "" : link.targetItemId())
                        + "|" + link.targetTitle() + "|" + link.relationStatus();
                dedup.putIfAbsent(key, link);
                if (dedup.size() >= NETWORK_DEDUP_LIMIT) {
                    return new ArrayList<>(dedup.values());
                }
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private List<KnowledgeCitationResponse> collectNetworkCitations(List<WikiPageContext> contexts) {
        Map<String, KnowledgeCitationResponse> dedup = new LinkedHashMap<>();
        for (WikiPageContext context : contexts) {
            for (KnowledgeCitationResponse citation : context.citations()) {
                String key = citation.sourceId() + "|" + citation.title() + "|" + citation.locationInfo();
                dedup.putIfAbsent(key, citation);
                if (dedup.size() >= NETWORK_DEDUP_LIMIT) {
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
