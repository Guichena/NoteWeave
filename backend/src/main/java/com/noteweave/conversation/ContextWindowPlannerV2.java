package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** C2 selection over already authorized references at a frozen input cutoff. */
public final class ContextWindowPlannerV2 {
    public static final String COMPILER_VERSION = "context-window-v2-a3";
    /** 原文窗口最多保留的消息条数。 */
    static final int MAX_RAW_TAIL_MESSAGES = 8;
    /** 原文窗口至少保留的消息条数（最近一问一答），再长也不裁掉。 */
    static final int MIN_RAW_TAIL_MESSAGES = 2;
    /** 原文窗口的 token 目标；摘要任务按同一目标决定覆盖到哪里，两边的边界保持一致。 */
    static final int RAW_TAIL_TOKEN_TARGET = 6_000;
    /** 原文窗口最多占总预算的比例，给摘要、约束和记忆留出空间。 */
    private static final double RAW_TAIL_BUDGET_SHARE = 0.5d;
    private static final int MAX_UNSUMMARIZED_MESSAGES = 256;
    /** 摘要异步生成，通常比原文窗口落后一两轮；落后不超过这个条数时用原文补齐，不整段展开。 */
    private static final int MAX_SUMMARY_LAG_MESSAGES = 8;

    public ContextProjectionV2 compile(Input input) {
        if (input == null || input.workspaceId().isBlank() || input.actorId().isBlank()
                || input.currentInput() == null || input.currentInput().isBlank()
                || input.tokenBudget() < 1 || input.cutoffSeq() < 0) {
            throw new IllegalArgumentException("context planner requires frozen identity, input and budget");
        }
        List<ContextProjectionV2.RawMessage> messages = input.messages().stream()
                .filter(message -> message.seq() <= input.cutoffSeq())
                .sorted(Comparator.comparingInt(ContextProjectionV2.RawMessage::seq)).toList();
        if (input.conversationId().isBlank() && (!messages.isEmpty()
                || !input.segments().isEmpty() || !input.constraints().isEmpty())) {
            throw new IllegalArgumentException("conversation data requires a conversation identity");
        }
        int lastSeq = 0;
        for (ContextProjectionV2.RawMessage message : messages) {
            if (message.seq() <= lastSeq) throw new IllegalArgumentException("duplicate message sequence");
            lastSeq = message.seq();
        }
        TopicSegmenterV2.Segment active = input.segments().stream()
                .filter(segment -> segment.startSeq() <= input.cutoffSeq()
                        && segment.endSeq() >= input.cutoffSeq())
                .findFirst().orElse(null);
        List<ContextProjectionV2.UserConstraint> constraints = input.constraints().stream()
                .filter(item -> "ACTIVE".equals(item.status())
                        && item.validFromSeq() <= input.cutoffSeq()
                        && (item.invalidAfterSeq() == null || item.invalidAfterSeq() > input.cutoffSeq())
                        && applies(item, input.taskPurpose(), active, input.segments(), messages))
                .toList();
        List<ContextProjectionV2.MemoryRevision> memories = List.copyOf(input.memoryRevisions());
        ArrayList<ContextProjectionV2.Decision> decisions = new ArrayList<>();
        for (ContextProjectionV2.UserConstraint item : input.constraints()) {
            decisions.add(new ContextProjectionV2.Decision("USER_CONSTRAINT", item.constraintId(),
                    constraints.contains(item) ? "INCLUDE" : "EXCLUDE",
                    constraints.contains(item) ? "ACTIVE_APPLICABLE" : "INACTIVE_OR_OUT_OF_SCOPE"));
        }
        for (ContextProjectionV2.MemoryRevision item : memories) {
            decisions.add(new ContextProjectionV2.Decision("MEMORY_REVISION", item.revisionId(),
                    "INCLUDE", "APPROVED_MEMORY_INPUT"));
        }
        int tailTokenLimit = Math.min(RAW_TAIL_TOKEN_TARGET,
                (int) (input.tokenBudget() * RAW_TAIL_BUDGET_SHARE));
        int tailSize = rawTailSize(messages.stream().map(ContextProjectionV2.RawMessage::text).toList(),
                tailTokenLimit);
        int firstTail = messages.size() - tailSize;
        List<ContextProjectionV2.RawMessage> raw = messages.subList(firstTail, messages.size());
        List<String> degradation = new ArrayList<>();
        if (tailSize < Math.min(MAX_RAW_TAIL_MESSAGES, messages.size())) {
            // 最近几条消息很长时原文窗口收窄，更早的消息交给摘要
            degradation.add("RAW_TAIL_TRIMMED_BY_TOKENS");
        }
        if (active != null && !raw.isEmpty() && active.startSeq() < raw.get(0).seq()) {
            int initialRawStart = raw.get(0).seq();
            // 当前话题段内覆盖到原文窗口之前、且最新的一版摘要
            int summaryEnd = input.readySummaries().stream()
                    .filter(summary -> summary.segmentId().equals(active.segmentId())
                            && summary.startSeq() == active.startSeq()
                            && summary.endSeq() < initialRawStart)
                    .mapToInt(ContextProjectionV2.TopicSummary::endSeq)
                    .max().orElse(-1);
            int lag = summaryEnd < 0 ? Integer.MAX_VALUE : initialRawStart - 1 - summaryEnd;
            if (lag > 0 && lag <= MAX_SUMMARY_LAG_MESSAGES) {
                // 摘要与原文窗口之间差几条消息：原文窗口向前延伸到摘要之后，不遗漏也不重复
                int bridgedStart = 0;
                while (bridgedStart < messages.size()
                        && messages.get(bridgedStart).seq() <= summaryEnd) bridgedStart++;
                raw = messages.subList(bridgedStart, messages.size());
                degradation.add("CURRENT_SUMMARY_LAGGING_RAW_BRIDGED");
            } else if (lag > 0) {
                int expandedStart = 0;
                while (expandedStart < messages.size()
                        && messages.get(expandedStart).seq() < active.startSeq()) expandedStart++;
                raw = messages.subList(expandedStart, messages.size());
                degradation.add("CURRENT_SUMMARY_NOT_READY_RAW_EXPANDED");
            }
        }
        if (raw.size() > MAX_UNSUMMARIZED_MESSAGES) {
            throw budgetExceeded("unsummarized raw message limit exceeded");
        }
        int used = tokens(input.currentInput());
        for (ContextProjectionV2.UserConstraint item : constraints) used += tokens(item.text());
        for (ContextProjectionV2.MemoryRevision item : memories) used += tokens(item.text());
        for (ContextProjectionV2.RawMessage item : raw) used += tokens(item.text());
        if (used > input.tokenBudget()) throw budgetExceeded("mandatory control and raw context exceed budget");
        for (ContextProjectionV2.RawMessage item : raw) {
            decisions.add(new ContextProjectionV2.Decision("MESSAGE", item.messageId(),
                    "INCLUDE", "CONTIGUOUS_RAW_TAIL"));
        }
        int rawStart = raw.isEmpty() ? input.cutoffSeq() + 1 : raw.get(0).seq();
        // 每个话题段取覆盖到原文窗口之前的最新一版摘要（增量摘要会留下多个版本）
        Map<String, ContextProjectionV2.TopicSummary> bySegment = new HashMap<>();
        for (ContextProjectionV2.TopicSummary summary : input.readySummaries()) {
            if (summary.endSeq() > input.cutoffSeq()) continue;
            ContextProjectionV2.TopicSummary current = bySegment.get(summary.segmentId());
            boolean fitsWindow = summary.endSeq() < rawStart;
            boolean currentFits = current != null && current.endSeq() < rawStart;
            if (current == null || (fitsWindow && (!currentFits || summary.endSeq() >= current.endSeq()))
                    || (!fitsWindow && !currentFits && summary.endSeq() >= current.endSeq())) {
                bySegment.put(summary.segmentId(), summary);
            }
        }
        ArrayList<ContextProjectionV2.TopicSummary> selected = new ArrayList<>();
        for (TopicSegmenterV2.Segment segment : input.segments()) {
            ContextProjectionV2.TopicSummary summary = bySegment.get(segment.segmentId());
            if (summary == null) continue;
            String reason;
            if (active == null || !active.topicId().equals(segment.topicId())) {
                reason = "OTHER_TOPIC";
            } else if (!segment.segmentId().equals(active.segmentId())
                    && ("UNCERTAIN".equals(active.status()) || "UNCERTAIN".equals(segment.status()))) {
                // 话题归属不确定时不引入其他段的摘要；当前段自身的摘要只覆盖本段更早的消息，可以使用
                reason = "UNCERTAIN_TOPIC";
            } else if (summary.startSeq() != segment.startSeq()
                    || summary.endSeq() > segment.endSeq() || summary.endSeq() >= rawStart) {
                reason = "OVERLAPS_RAW_OR_STALE_RANGE";
            } else if (used + tokens(summary.text()) > input.tokenBudget()) {
                reason = "SUMMARY_BUDGET_EXCEEDED";
                degradation.add(reason);
            } else {
                selected.add(summary);
                used += tokens(summary.text());
                reason = "MATCHED_ACTIVE_TOPIC";
            }
            decisions.add(new ContextProjectionV2.Decision("TOPIC_SUMMARY", summary.revisionId(),
                    "MATCHED_ACTIVE_TOPIC".equals(reason) ? "INCLUDE" : "EXCLUDE", reason));
        }
        return new ContextProjectionV2("context-projection-v2", COMPILER_VERSION,
                input.workspaceId(), input.actorId(), input.conversationId(), input.cutoffSeq(),
                input.currentInput(), raw, selected, constraints, memories, decisions,
                input.tokenBudget(), used, degradation, "FULL");
    }

    /**
     * 从最后一条消息往前数，原文窗口保留多少条：至少 MIN_RAW_TAIL_MESSAGES 条，最多 MAX_RAW_TAIL_MESSAGES 条，
     * 中间按 token 累计，超过 tokenLimit 就停。短消息的对话保留满 8 条，长回答的对话只保留最近几轮。
     */
    static int rawTailSize(List<String> textsInOrder, int tokenLimit) {
        int count = 0;
        int used = 0;
        for (int index = textsInOrder.size() - 1; index >= 0 && count < MAX_RAW_TAIL_MESSAGES; index--) {
            int cost = tokens(textsInOrder.get(index));
            if (count >= MIN_RAW_TAIL_MESSAGES && used + cost > tokenLimit) break;
            used += cost;
            count++;
        }
        return count;
    }

    /** 预算单位是估算的 token 数，估算规则见 ContextTokenEstimator。 */
    private static int tokens(String value) {
        return ContextTokenEstimator.estimate(value);
    }

    private static boolean applies(ContextProjectionV2.UserConstraint constraint, String taskPurpose,
                                   TopicSegmenterV2.Segment active,
                                   List<TopicSegmenterV2.Segment> segments,
                                   List<ContextProjectionV2.RawMessage> messages) {
        if ("GLOBAL".equals(constraint.scope()) || "CONVERSATION".equals(constraint.scope())
                || constraint.scope().equals(taskPurpose)) return true;
        if (!"CURRENT_TOPIC".equals(constraint.scope()) || active == null
                || !"CONFIDENT".equals(active.status())) return false;
        int sourceSeq = messages.stream()
                .filter(message -> message.messageId().equals(constraint.sourceMessageId()))
                .mapToInt(ContextProjectionV2.RawMessage::seq).findFirst().orElse(-1);
        return segments.stream().anyMatch(segment -> segment.startSeq() <= sourceSeq
                && segment.endSeq() >= sourceSeq && "CONFIDENT".equals(segment.status())
                && segment.topicId().equals(active.topicId()));
    }

    private static BusinessException budgetExceeded(String reason) {
        return new BusinessException("CONTEXT_BUDGET_EXCEEDED", reason, HttpStatus.CONFLICT);
    }

    public record Input(String workspaceId, String actorId, String conversationId, int cutoffSeq,
                        String currentInput, String taskPurpose, int tokenBudget,
                        List<ContextProjectionV2.RawMessage> messages,
                        List<TopicSegmenterV2.Segment> segments,
                        List<ContextProjectionV2.TopicSummary> readySummaries,
                        List<ContextProjectionV2.UserConstraint> constraints,
                        List<ContextProjectionV2.MemoryRevision> memoryRevisions) {}
}
