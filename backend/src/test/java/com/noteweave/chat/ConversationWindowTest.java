package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.answer.strategy.AnswerContext;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationWindowTest {

    @Test
    void labelsWindowAsContextCarryingTopicSummaryAndConstraints() {
        String window = "当前问题：那它的 K 取多少？\n"
                + "已冻结的相关主题摘要：\nRRF 用 K 平滑排名差异\n"
                + "当前有效的用户约束：\n- 请用中文";
        StringBuilder builder = new StringBuilder();

        ConversationWindow.append(builder, context(Map.of(ConversationWindow.ATTRIBUTE, window)));

        assertThat(builder.toString())
                .isEqualTo("最近对话上下文（含主题摘要与用户约束，不要向用户复述）：\n" + window + "\n\n")
                .contains("最近对话上下文")
                .doesNotContain("最近对话原文");
    }

    @Test
    void skipsBlankOrMissingWindow() {
        StringBuilder blank = new StringBuilder();
        ConversationWindow.append(blank, context(Map.of(ConversationWindow.ATTRIBUTE, "   ")));
        assertThat(blank.toString()).isEmpty();

        StringBuilder missing = new StringBuilder();
        ConversationWindow.append(missing, context(Map.of()));
        assertThat(missing.toString()).isEmpty();

        StringBuilder nullContext = new StringBuilder();
        ConversationWindow.append(nullContext, null);
        assertThat(nullContext.toString()).isEmpty();
    }

    private AnswerContext context(Map<String, String> attributes) {
        return new AnswerContext("workspace", "conversation", "message", "query",
                java.util.Set.of(), attributes, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
