package com.noteweave.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 分层压缩：把账本投影按预算选择成原文、消息摘要、话题摘要与句柄。
 *
 * <p>选择顺序固定：最近一轮优先原文；较早内容按预算逐级退到消息摘要、话题摘要与句柄。
 * 助手输出超过原文目标时不整段展开，改用摘要或句柄并记录降级。任何降级都写进原因清单，
 * 渲染阶段不得再截字。摘要与句柄永远保留自己的形态，不会被描述成已恢复的原文。</p>
 *
 * <p>覆盖范围必须连续且互不重叠：一条消息要么由原文覆盖，要么由某一级摘要覆盖，
 * 要么退成句柄或占位。没覆盖到的消息必须留下剔除原因。</p>
 */
public final class ContextCompressor {

    /** 压缩实现版本，改动选择规则必须递增。 */
    public static final String COMPRESSOR_VERSION = "context-compression-v1";

    /** 较早回合改用消息摘要。 */
    public static final String REASON_EARLIER_TURNS_USE_SUMMARY = "EARLIER_TURNS_USE_SUMMARY";
    /** 较早回合改用话题摘要。 */
    public static final String REASON_EARLIER_TURNS_USE_TOPIC_SUMMARY = "EARLIER_TURNS_USE_TOPIC_SUMMARY";
    /** 没有可用摘要，退成句柄。 */
    public static final String REASON_EARLIER_TURNS_USE_HANDLE = "EARLIER_TURNS_USE_HANDLE";
    /** 助手输出超过原文目标。 */
    public static final String REASON_ASSISTANT_OUTPUT_EXCEEDS_RAW_TARGET =
            "ASSISTANT_OUTPUT_EXCEEDS_RAW_TARGET";
    /** 原文预算不足。 */
    public static final String REASON_RAW_TRIMMED_BY_TOKENS = "RAW_TRIMMED_BY_TOKENS";
    /** 摘要预算不足。 */
    public static final String REASON_SUMMARY_BUDGET_EXCEEDED = "SUMMARY_BUDGET_EXCEEDED";
    /** 句柄预算不足。 */
    public static final String REASON_HANDLE_BUDGET_EXCEEDED = "HANDLE_BUDGET_EXCEEDED";
    /** 该消息超出上下文窗口。 */
    public static final String REASON_OUTSIDE_CONTEXT_WINDOW = "OUTSIDE_CONTEXT_WINDOW";
    /** 摘要与原文窗口重叠或范围已经过期。 */
    public static final String REASON_OVERLAPS_OR_STALE = "OVERLAPS_OR_STALE";
    /** 摘要属于其他话题。 */
    public static final String REASON_OTHER_TOPIC = "OTHER_TOPIC";

    /** 按预算选择材料。 */
    public Selection compress(Input input) {
        if (input == null || input.cutoffSeq() < 0) {
            throw new IllegalArgumentException("compression requires a frozen ledger and cutoff");
        }
        List<ConversationLedgerProjection.Projected> entries = new ArrayList<>();
        for (ConversationLedgerProjection.Projected entry : input.entries()) {
            if (entry.seq() <= input.cutoffSeq()) {
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparingInt(ConversationLedgerProjection.Projected::seq));
        List<SummaryBlock> summaries = new ArrayList<>(input.summaries());
        summaries.sort(Comparator.comparingInt(SummaryBlock::startSeq)
                .thenComparingInt(SummaryBlock::endSeq)
                .thenComparing(SummaryBlock::refId));

        List<ContextView.Item> items = new ArrayList<>();
        List<ContextView.Exclusion> exclusions = new ArrayList<>();
        Set<String> degradation = new LinkedHashSet<>();
        Set<String> usedSummaries = new HashSet<>();
        int rawBudget = Math.max(0, input.rawTargetTokens());
        int summaryBudget = Math.max(0, input.summaryTargetTokens());
        int handleBudget = Math.max(0, input.handleTargetTokens());
        int rawCount = 0;
        int coveredStart = input.cutoffSeq() + 1;
        int coveredEnd = 0;

        // 从最新一条往前选，保证最近一轮优先原文，且覆盖范围连续。
        for (int index = entries.size() - 1; index >= 0; index--) {
            ConversationLedgerProjection.Projected entry = entries.get(index);
            if (entry.form() == ContextItemForm.PLACEHOLDER) {
                items.add(ContextView.Item.of(ContextLayer.MESSAGE_HANDLE, ContextItemForm.PLACEHOLDER,
                        entry.refType(), entry.refId(), entry.seq(), entry.seq(),
                        placeholderText(entry)));
                coveredStart = Math.min(coveredStart, entry.seq());
                coveredEnd = Math.max(coveredEnd, entry.seq());
                continue;
            }
            if (entry.form() == ContextItemForm.HANDLE) {
                // 研究进行中的消息：任何历史位置都保留句柄，不假定只有末尾一条占位
                items.add(ContextView.Item.of(ContextLayer.MESSAGE_HANDLE, ContextItemForm.HANDLE,
                        entry.refType(), entry.refId(), entry.seq(), entry.seq(),
                        "该回合的研究尚未完成，需要时按句柄回忆"));
                coveredStart = Math.min(coveredStart, entry.seq());
                coveredEnd = Math.max(coveredEnd, entry.seq());
                continue;
            }
            int cost = ContextView.estimate(entry.text());
            boolean overRawTarget = entry.text().length() > 0
                    && cost > input.rawTargetTokens()
                    && "ASSISTANT".equals(entry.role());
            if (rawCount < input.recentRawMessages() && !overRawTarget && cost <= rawBudget) {
                items.add(ContextView.Item.of(ContextLayer.RAW_MESSAGE, ContextItemForm.RAW,
                        ContextRefType.MESSAGE, entry.messageId(), entry.seq(), entry.seq(), entry.text()));
                rawBudget -= cost;
                rawCount++;
                coveredStart = Math.min(coveredStart, entry.seq());
                coveredEnd = Math.max(coveredEnd, entry.seq());
                continue;
            }
            if (overRawTarget) {
                degradation.add(REASON_ASSISTANT_OUTPUT_EXCEEDS_RAW_TARGET);
            } else if (rawCount < input.recentRawMessages()) {
                degradation.add(REASON_RAW_TRIMMED_BY_TOKENS);
            }
            SummaryBlock summary = selectSummary(summaries, usedSummaries, entry.seq(),
                    input.cutoffSeq(), coveredStart);
            if (summary != null && summaryBudget >= ContextView.estimate(summary.text())) {
                usedSummaries.add(summary.refId());
                ContextLayer layer = summary.kind() == ContextItemForm.TOPIC_SUMMARY
                        ? ContextLayer.TOPIC_SUMMARY : ContextLayer.MESSAGE_SUMMARY;
                items.add(ContextView.Item.of(layer, summary.kind(), ContextRefType.TOPIC_SUMMARY,
                        summary.refId(), summary.startSeq(), summary.endSeq(), summary.text()));
                summaryBudget -= ContextView.estimate(summary.text());
                degradation.add(summary.kind() == ContextItemForm.TOPIC_SUMMARY
                        ? REASON_EARLIER_TURNS_USE_TOPIC_SUMMARY : REASON_EARLIER_TURNS_USE_SUMMARY);
                coveredStart = Math.min(coveredStart, summary.startSeq());
                coveredEnd = Math.max(coveredEnd, summary.endSeq());
                // 摘要覆盖的序号整体跳过
                index = skipCovered(entries, index, summary.startSeq());
                continue;
            }
            if (summary != null) {
                degradation.add(REASON_SUMMARY_BUDGET_EXCEEDED);
            }
            int handleCost = ContextView.estimate(entry.text()) / 8 + 8;
            if (handleBudget >= handleCost) {
                handleBudget -= handleCost;
                items.add(ContextView.Item.of(ContextLayer.MESSAGE_HANDLE, ContextItemForm.HANDLE,
                        ContextRefType.MESSAGE, entry.messageId(), entry.seq(), entry.seq(),
                        "需要展开时按句柄回忆这条回合"));
                degradation.add(REASON_EARLIER_TURNS_USE_HANDLE);
                coveredStart = Math.min(coveredStart, entry.seq());
                coveredEnd = Math.max(coveredEnd, entry.seq());
                continue;
            }
            degradation.add(REASON_HANDLE_BUDGET_EXCEEDED);
            exclusions.add(new ContextView.Exclusion(ContextRefType.MESSAGE, entry.messageId(),
                    ContextLayer.RAW_MESSAGE, REASON_OUTSIDE_CONTEXT_WINDOW, "seq=" + entry.seq()));
        }
        for (SummaryBlock summary : summaries) {
            if (usedSummaries.contains(summary.refId())) {
                continue;
            }
            ContextLayer layer = summary.kind() == ContextItemForm.TOPIC_SUMMARY
                    ? ContextLayer.TOPIC_SUMMARY : ContextLayer.MESSAGE_SUMMARY;
            exclusions.add(new ContextView.Exclusion(ContextRefType.TOPIC_SUMMARY, summary.refId(), layer,
                    summary.endSeq() >= coveredStart ? REASON_OVERLAPS_OR_STALE : REASON_OTHER_TOPIC,
                    "range=" + summary.startSeq() + "-" + summary.endSeq()));
        }
        // 选择是从新到旧做的，落进视图前按契约顺序重排：话题摘要、消息摘要、原文、句柄。
        items.sort(Comparator.comparingInt((ContextView.Item item) -> layerRank(item.layer()))
                .thenComparingInt(ContextView.Item::startSeq)
                .thenComparing(ContextView.Item::itemId));
        return new Selection(List.copyOf(items), List.copyOf(exclusions), List.copyOf(degradation),
                Math.min(coveredStart, input.cutoffSeq() + 1), Math.max(coveredEnd, 0));
    }

    /** 压缩只产出会话材料，这四层的相对顺序在各调用点一致。 */
    private static int layerRank(ContextLayer layer) {
        return switch (layer) {
            case TOPIC_SUMMARY -> 0;
            case MESSAGE_SUMMARY -> 1;
            case RAW_MESSAGE -> 2;
            case MESSAGE_HANDLE -> 3;
            default -> 4;
        };
    }

    private String placeholderText(ConversationLedgerProjection.Projected entry) {
        return switch (entry.reason()) {
            case ConversationLedgerProjector.REASON_DELETED_LEDGER_POSITION_ONLY ->
                    "该回合已删除，只保留位置，不提供正文";
            case ConversationLedgerProjector.REASON_FAILED_PLACEHOLDER ->
                    "该回合执行失败，没有可用正文";
            case ConversationLedgerProjector.REASON_CANCELLED_PLACEHOLDER ->
                    "该回合已取消，没有可用正文";
            default -> "该回合尚未完成，没有可用正文";
        };
    }

    private SummaryBlock selectSummary(List<SummaryBlock> summaries, Set<String> used, int seq,
                                       int cutoffSeq, int coveredStart) {
        SummaryBlock messageLevel = null;
        SummaryBlock topicLevel = null;
        for (SummaryBlock summary : summaries) {
            if (used.contains(summary.refId()) || summary.startSeq() > seq || summary.endSeq() < seq
                    || summary.endSeq() > cutoffSeq || summary.endSeq() >= coveredStart) {
                continue;
            }
            if (summary.kind() == ContextItemForm.MESSAGE_SUMMARY) {
                if (messageLevel == null || summary.endSeq() > messageLevel.endSeq()) {
                    messageLevel = summary;
                }
            } else if (topicLevel == null || summary.endSeq() > topicLevel.endSeq()) {
                topicLevel = summary;
            }
        }
        return messageLevel != null ? messageLevel : topicLevel;
    }

    private int skipCovered(List<ConversationLedgerProjection.Projected> entries, int index, int startSeq) {
        int next = index;
        while (next > 0 && entries.get(next - 1).seq() >= startSeq) {
            next--;
        }
        return next;
    }

    /**
     * 压缩输入。
     *
     * @param conversationId     会话身份
     * @param cutoffSeq          冻结截止序号
     * @param entries            冻结账本投影后的条目
     * @param summaries          可用摘要，含消息摘要与话题摘要
     * @param rawTargetTokens    原文预算
     * @param summaryTargetTokens 摘要预算
     * @param handleTargetTokens 句柄预算
     * @param recentRawMessages  最近保留原文的消息条数
     * @param summaryVersion     摘要版本，写进契约
     */
    public record Input(
            String conversationId,
            int cutoffSeq,
            List<ConversationLedgerProjection.Projected> entries,
            List<SummaryBlock> summaries,
            int rawTargetTokens,
            int summaryTargetTokens,
            int handleTargetTokens,
            int recentRawMessages,
            String summaryVersion
    ) {
        public Input {
            conversationId = conversationId == null ? "" : conversationId;
            entries = entries == null ? List.of() : List.copyOf(entries);
            summaries = summaries == null ? List.of() : List.copyOf(summaries);
            summaryVersion = summaryVersion == null ? "" : summaryVersion;
            if (recentRawMessages < 0) {
                throw new IllegalArgumentException("recent raw message count cannot be negative");
            }
        }
    }

    /**
     * 摘要块。
     *
     * @param kind      形态，MESSAGE_SUMMARY 或 TOPIC_SUMMARY
     * @param refId     摘要版本身份
     * @param startSeq  覆盖起始序号
     * @param endSeq    覆盖结束序号
     * @param text      摘要正文
     * @param status    摘要状态
     */
    public record SummaryBlock(ContextItemForm kind, String refId, int startSeq, int endSeq,
                               String text, String status) {
        public SummaryBlock {
            text = text == null ? "" : text;
            status = status == null ? "" : status;
            if (kind != ContextItemForm.MESSAGE_SUMMARY && kind != ContextItemForm.TOPIC_SUMMARY) {
                throw new IllegalArgumentException("summary block must be a summary form");
            }
            if (refId == null || refId.isBlank() || startSeq < 1 || endSeq < startSeq) {
                throw new IllegalArgumentException("summary block range or identity is invalid");
            }
        }
    }

    /**
     * 选择结果。
     *
     * @param items           选中的条目，已按账本顺序排列
     * @param exclusions      未选中的候选及原因
     * @param degradationReasons 降级原因
     * @param coveredStartSeq 覆盖起始序号
     * @param coveredEndSeq   覆盖结束序号
     */
    public record Selection(List<ContextView.Item> items, List<ContextView.Exclusion> exclusions,
                            List<String> degradationReasons, int coveredStartSeq, int coveredEndSeq) {
        public Selection {
            items = items == null ? List.of() : List.copyOf(items);
            exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
            degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
        }
    }
}
