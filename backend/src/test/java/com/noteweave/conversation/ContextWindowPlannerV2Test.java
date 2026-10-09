package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextWindowPlannerV2Test {
    private final ContextWindowPlannerV2 planner = new ContextWindowPlannerV2();

    @Test
    void returnToOldTopicKeepsContinuousTailAndApplicableNegativeConstraint() {
        List<ContextProjectionV2.RawMessage> messages = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(seq -> message(seq, "message " + seq)).toList();
        List<TopicSegmenterV2.Segment> segments = List.of(
                segment("A1", "A", 1, 2, "CONFIDENT"),
                segment("B1", "B", 3, 8, "CONFIDENT"),
                segment("A2", "A", 9, 10, "CONFIDENT"));
        ContextProjectionV2.TopicSummary a = summary("A1", "A", 1, 2, "A older facts");
        ContextProjectionV2.TopicSummary b = summary("B1", "B", 3, 8, "B unrelated facts");
        ContextProjectionV2.UserConstraint negative = new ContextProjectionV2.UserConstraint(
                "constraint-1", "message-9", "USER", "NEGATIVE", "GLOBAL",
                "不要上一版", 9, null, "ACTIVE");
        ContextProjectionV2 result = planner.compile(new ContextWindowPlannerV2.Input(
                "workspace", "actor", "conversation", 10, "回到 A，第二门呢？", "QA", 300,
                messages, segments, List.of(a, b), List.of(negative), List.of()));

        assertThat(result.rawTail()).extracting(ContextProjectionV2.RawMessage::seq)
                .containsExactly(3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(result.topicSummaries()).containsExactly(a);
        assertThat(result.constraints()).containsExactly(negative);
        assertThat(result.decisions()).anySatisfy(decision -> {
            assertThat(decision.refId()).isEqualTo("revision-B1");
            assertThat(decision.action()).isEqualTo("EXCLUDE");
            assertThat(decision.reason()).isEqualTo("OTHER_TOPIC");
        });
        assertThat(result.selectedTokens()).isLessThanOrEqualTo(result.tokenBudget());
    }

    @Test
    void missingCurrentSummaryExpandsRawAndFailsExplicitlyIfControlDoesNotFit() {
        List<ContextProjectionV2.RawMessage> messages = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(seq -> message(seq, "long message " + seq)).toList();
        var segment = segment("A1", "A", 1, 10, "CONFIDENT");
        var input = new ContextWindowPlannerV2.Input("workspace", "actor", "conversation", 10,
                "current question", "QA", 300, messages, List.of(segment), List.of(),
                List.of(), List.of());
        ContextProjectionV2 result = planner.compile(input);
        assertThat(result.rawTail()).hasSize(10);
        assertThat(result.degradationReasons()).contains("CURRENT_SUMMARY_NOT_READY_RAW_EXPANDED");
        assertThatThrownBy(() -> planner.compile(new ContextWindowPlannerV2.Input(
                "workspace", "actor", "conversation", 10, "current question", "QA", 20,
                messages, List.of(segment), List.of(), List.of(), List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("mandatory control and raw context exceed budget");
    }

    @Test
    void uncertainTopicDoesNotBringRemoteSummaryIntoWindow() {
        List<ContextProjectionV2.RawMessage> messages = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(seq -> message(seq, "message " + seq)).toList();
        var uncertain = segment("A2", "A", 9, 10, "UNCERTAIN");
        var previous = segment("A1", "A", 1, 2, "CONFIDENT");
        ContextProjectionV2 result = planner.compile(new ContextWindowPlannerV2.Input(
                "workspace", "actor", "conversation", 10, "那个呢？", "QA", 300,
                messages, List.of(previous, segment("B1", "B", 3, 8, "CONFIDENT"), uncertain),
                List.of(summary("A1", "A", 1, 2, "old facts")), List.of(), List.of()));
        assertThat(result.topicSummaries()).isEmpty();
        assertThat(result.decisions()).anySatisfy(decision -> {
            assertThat(decision.refId()).isEqualTo("revision-A1");
            assertThat(decision.reason()).isEqualTo("UNCERTAIN_TOPIC");
        });
    }

    @Test
    void partialCurrentSummaryCannotHideMessagesBetweenSummaryAndRawTail() {
        List<ContextProjectionV2.RawMessage> messages = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(seq -> message(seq, "message " + seq)).toList();
        var active = segment("A1", "A", 1, 20, "CONFIDENT");
        // 摘要落后原文窗口 9 条，超过可补齐的范围，整段展开为原文
        var partial = summary("A1", "A", 1, 3, "only first three messages");
        ContextProjectionV2 result = planner.compile(new ContextWindowPlannerV2.Input(
                "workspace", "actor", "conversation", 20, "question", "QA", 500,
                messages, List.of(active), List.of(partial), List.of(), List.of()));

        assertThat(result.rawTail()).hasSize(20);
        assertThat(result.degradationReasons()).contains("CURRENT_SUMMARY_NOT_READY_RAW_EXPANDED");
        assertThat(result.topicSummaries()).isEmpty();
    }

    @Test
    void contiguousCurrentSummaryKeepsEightMessageRawTail() {
        List<ContextProjectionV2.RawMessage> messages = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(seq -> message(seq, "message " + seq)).toList();
        var active = segment("A1", "A", 1, 20, "CONFIDENT");
        var complete = summary("A1", "A", 1, 12, "first twelve messages");
        ContextProjectionV2 result = planner.compile(new ContextWindowPlannerV2.Input(
                "workspace", "actor", "conversation", 20, "question", "QA", 500,
                messages, List.of(active), List.of(complete), List.of(), List.of()));

        assertThat(result.rawTail()).extracting(ContextProjectionV2.RawMessage::seq)
                .containsExactly(13, 14, 15, 16, 17, 18, 19, 20);
        assertThat(result.topicSummaries()).containsExactly(complete);
    }

    @Test
    void laggingIncrementalSummaryIsBridgedWithRawMessagesInsteadOfExpandingTheTopic() {
        List<ContextProjectionV2.RawMessage> messages = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(seq -> message(seq, "message " + seq)).toList();
        var active = segment("A1", "A", 1, 20, "CONFIDENT");
        // 增量摘要留下多个版本；最新一版还在生成，已就绪的一版覆盖到第 10 条
        var older = new ContextProjectionV2.TopicSummary("A", "A1", "revision-A1-1", 1, 6, "first six", digest("first six"));
        var ready = new ContextProjectionV2.TopicSummary("A", "A1", "revision-A1-2", 1, 10, "first ten", digest("first ten"));
        ContextProjectionV2 result = planner.compile(new ContextWindowPlannerV2.Input(
                "workspace", "actor", "conversation", 20, "question", "QA", 500,
                messages, List.of(active), List.of(older, ready), List.of(), List.of()));

        assertThat(result.rawTail()).extracting(ContextProjectionV2.RawMessage::seq)
                .containsExactly(11, 12, 13, 14, 15, 16, 17, 18, 19, 20);
        assertThat(result.topicSummaries()).containsExactly(ready);
        assertThat(result.degradationReasons()).containsExactly("CURRENT_SUMMARY_LAGGING_RAW_BRIDGED");
    }

    @Test
    void uncertainActiveSegmentStillUsesItsOwnSummary() {
        List<ContextProjectionV2.RawMessage> messages = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(seq -> message(seq, "message " + seq)).toList();
        var first = segment("A1", "A", 1, 2, "CONFIDENT");
        var active = segment("B1", "B", 3, 20, "UNCERTAIN");
        var own = summary("B1", "B", 3, 12, "own earlier messages");
        ContextProjectionV2 result = planner.compile(new ContextWindowPlannerV2.Input(
                "workspace", "actor", "conversation", 20, "question", "QA", 500,
                messages, List.of(first, active), List.of(summary("A1", "A", 1, 2, "other topic"), own),
                List.of(), List.of()));

        assertThat(result.rawTail()).extracting(ContextProjectionV2.RawMessage::seq)
                .containsExactly(13, 14, 15, 16, 17, 18, 19, 20);
        assertThat(result.topicSummaries()).containsExactly(own);
        assertThat(result.decisions()).anySatisfy(decision -> {
            assertThat(decision.refId()).isEqualTo("revision-A1");
            assertThat(decision.reason()).isEqualTo("OTHER_TOPIC");
        });
    }

    @Test
    void currentTopicCorrectionSurvivesReturnButOtherTopicRuleDoesNot() {
        List<ContextProjectionV2.RawMessage> messages = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(seq -> message(seq, "message " + seq)).toList();
        List<TopicSegmenterV2.Segment> segments = List.of(
                segment("A1", "A", 1, 2, "CONFIDENT"),
                segment("B1", "B", 3, 8, "CONFIDENT"),
                segment("A2", "A", 9, 10, "CONFIDENT"));
        var aRule = new ContextProjectionV2.UserConstraint("a-rule", "message-1", "USER",
                "LANGUAGE", "CURRENT_TOPIC", "中文", 1, null, "ACTIVE");
        var bRule = new ContextProjectionV2.UserConstraint("b-rule", "message-3", "USER",
                "FORMAT", "CURRENT_TOPIC", "表格", 3, null, "ACTIVE");
        var conversationRule = new ContextProjectionV2.UserConstraint("conversation-rule", "message-4", "USER",
                "FORMAT", "CONVERSATION", "先给结论", 4, null, "ACTIVE");
        ContextProjectionV2 result = planner.compile(new ContextWindowPlannerV2.Input(
                "workspace", "actor", "conversation", 10, "回到 A", "QA", 500,
                messages, segments, List.of(), List.of(aRule, bRule, conversationRule), List.of()));

        assertThat(result.constraints()).containsExactly(aRule, conversationRule);
        assertThat(result.decisions()).anySatisfy(decision -> {
            assertThat(decision.refId()).isEqualTo("b-rule");
            assertThat(decision.action()).isEqualTo("EXCLUDE");
        });
    }

    private static ContextProjectionV2.RawMessage message(int seq, String text) {
        return new ContextProjectionV2.RawMessage("message-" + seq, seq, "USER", text, digest(text));
    }

    private static TopicSegmenterV2.Segment segment(String id, String topic, int start, int end, String status) {
        return new TopicSegmenterV2.Segment(id, topic, start, end, status, "TEST", TopicSegmenterV2.RULE_VERSION);
    }

    private static ContextProjectionV2.TopicSummary summary(String id, String topic,
                                                              int start, int end, String text) {
        return new ContextProjectionV2.TopicSummary(topic, id, "revision-" + id,
                start, end, text, digest(text));
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
