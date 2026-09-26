package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Shadow C2 selection over already authorized, frozen references. No task consumes it yet. */
public final class ContextWindowPlannerV2 {
    public static final String COMPILER_VERSION = "context-window-v2-shadow-a1";
    private static final int TARGET_RAW_TAIL = 8;
    private static final int MAX_UNSUMMARIZED_MESSAGES = 256;

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
        int firstTail = Math.max(0, messages.size() - TARGET_RAW_TAIL);
        List<ContextProjectionV2.RawMessage> raw = messages.subList(firstTail, messages.size());
        List<String> degradation = new ArrayList<>();
        if (active != null && !raw.isEmpty() && active.startSeq() < raw.get(0).seq()) {
            int initialRawStart = raw.get(0).seq();
            boolean hasCurrentSummary = input.readySummaries().stream().anyMatch(summary ->
                    summary.segmentId().equals(active.segmentId())
                            && summary.startSeq() == active.startSeq()
                            && summary.endSeq() == initialRawStart - 1);
            if (!hasCurrentSummary) {
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
        Map<String, ContextProjectionV2.TopicSummary> bySegment = new HashMap<>();
        for (ContextProjectionV2.TopicSummary summary : input.readySummaries()) {
            if (summary.endSeq() <= input.cutoffSeq()) bySegment.put(summary.segmentId(), summary);
        }
        ArrayList<ContextProjectionV2.TopicSummary> selected = new ArrayList<>();
        int rawStart = raw.isEmpty() ? input.cutoffSeq() + 1 : raw.get(0).seq();
        for (TopicSegmenterV2.Segment segment : input.segments()) {
            ContextProjectionV2.TopicSummary summary = bySegment.get(segment.segmentId());
            if (summary == null) continue;
            String reason;
            if (active == null || !active.topicId().equals(segment.topicId())) {
                reason = "OTHER_TOPIC";
            } else if ("UNCERTAIN".equals(active.status())
                    || "UNCERTAIN".equals(segment.status())) {
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

    /** UTF-8 byte count is a conservative budget unit until a model tokenizer is pinned. */
    private static int tokens(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
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
