package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.conversation.ContextProjectionV2;
import com.noteweave.conversation.ContextV2ShadowSnapshotService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationRetrievalContextAssemblerV2Test {
    @Test
    void rendersOnlySelectedFrozenHistorySummaryAndUserControl() throws Exception {
        String old = "缓存一致性先写日志";
        String current = "第二种写入顺序呢？";
        String topic = "缓存主题的冻结摘要";
        String memory = "仅供控制的 Memory，不是引用证据";
        var projection = new ContextProjectionV2("context-projection-v2", "context-window-v2-test",
                "workspace", "actor", "conversation", 3, current,
                List.of(new ContextProjectionV2.RawMessage("old", 2, "USER", old, sha(old)),
                        new ContextProjectionV2.RawMessage("current", 3, "USER", current, sha(current))),
                List.of(new ContextProjectionV2.TopicSummary("topic", "segment", "revision",
                        1, 1, topic, sha(topic))),
                List.of(new ContextProjectionV2.UserConstraint("constraint", "old", "USER",
                        "FORMAT", "CONVERSATION", "请用中文", 2, null, "ACTIVE")),
                List.of(new ContextProjectionV2.MemoryRevision("memory", "memory-revision",
                        memory, sha(memory))), List.of(), 4096, 200, List.of(), "FULL");
        var frozen = new ContextV2ShadowSnapshotService.FrozenAnswer("snapshot", sha("snapshot"), projection);
        var context = new ConversationRetrievalContextAssembler().assembleV2(
                "current", current, frozen);
        assertThat(context.frozenV2()).isSameAs(frozen);
        assertThat(context.retrievalQuestion()).contains(old, current, topic, "请用中文")
                .doesNotContain(memory);
        assertThat(context.retrievalQuestion().split(current, -1)).hasSize(2);
        assertThat(context.inputProjection().recentMessageRefs()).isEmpty();
        assertThat(ChatService.renderV2UserConstraints(frozen))
                .contains("当前有效的用户约束", "请用中文")
                .doesNotContain(memory, old);
    }

    @Test
    void truncatesFrozenRawWindowWithV1Budgets() throws Exception {
        String current = "第二种写入顺序呢？";
        String longUser = "用户上下文" + "甲".repeat(200);
        String longAssistant = "助手上下文" + "乙".repeat(300);
        var projection = new ContextProjectionV2("context-projection-v2", "context-window-v2-test",
                "workspace", "actor", "conversation", 4, current,
                List.of(new ContextProjectionV2.RawMessage("old", 2, "USER", longUser, sha(longUser)),
                        new ContextProjectionV2.RawMessage("old-answer", 3, "ASSISTANT", longAssistant,
                                sha(longAssistant)),
                        new ContextProjectionV2.RawMessage("current", 4, "USER", current, sha(current))),
                List.of(), List.of(), List.of(), List.of(), 4096, 200, List.of(), "FULL");
        var frozen = new ContextV2ShadowSnapshotService.FrozenAnswer("snapshot", sha("snapshot"), projection);

        var context = new ConversationRetrievalContextAssembler().assembleV2("current", current, frozen);

        String expectedUser = longUser.substring(0, 119) + "...";
        String expectedAssistant = longAssistant.substring(0, 179) + "...";
        assertThat(expectedUser).hasSize(122);
        assertThat(expectedAssistant).hasSize(182);
        assertThat(context.retrievalQuestion())
                .contains("- USER：" + expectedUser)
                .contains("- ASSISTANT：" + expectedAssistant)
                .doesNotContain(longUser)
                .doesNotContain(longAssistant);
    }

    @Test
    void keepsShortFrozenRawWindowUntouched() throws Exception {
        String current = "第二种写入顺序呢？";
        String user = "缓存一致性先写日志";
        String answer = "先写日志再更新缓存";
        var projection = new ContextProjectionV2("context-projection-v2", "context-window-v2-test",
                "workspace", "actor", "conversation", 4, current,
                List.of(new ContextProjectionV2.RawMessage("old", 2, "USER", user, sha(user)),
                        new ContextProjectionV2.RawMessage("old-answer", 3, "ASSISTANT", answer, sha(answer)),
                        new ContextProjectionV2.RawMessage("current", 4, "USER", current, sha(current))),
                List.of(), List.of(), List.of(), List.of(), 4096, 200, List.of(), "FULL");
        var frozen = new ContextV2ShadowSnapshotService.FrozenAnswer("snapshot", sha("snapshot"), projection);

        var context = new ConversationRetrievalContextAssembler().assembleV2("current", current, frozen);

        assertThat(context.retrievalQuestion())
                .contains("- USER：" + user)
                .contains("- ASSISTANT：" + answer)
                .doesNotContain("...");
    }

    @Test
    void derivesTopicAnchorFromTheFrozenWindowSoFollowUpsKeepTheirTopic() throws Exception {
        String first = "先介绍 AlphaSpec 的关键要求";
        String firstAnswer = "AlphaSpec 要求分阶段发布和证据留痕";
        String second = "把它分成两点讲";
        String secondAnswer = "第一点是分阶段发布，第二点是证据留痕";
        String current = "第二点为什么重要";
        var projection = new ContextProjectionV2("context-projection-v2", "context-window-v2-test",
                "workspace", "actor", "conversation", 5, current,
                List.of(new ContextProjectionV2.RawMessage("m1", 1, "USER", first, sha(first)),
                        new ContextProjectionV2.RawMessage("m2", 2, "ASSISTANT", firstAnswer, sha(firstAnswer)),
                        new ContextProjectionV2.RawMessage("m3", 3, "USER", second, sha(second)),
                        new ContextProjectionV2.RawMessage("m4", 4, "ASSISTANT", secondAnswer, sha(secondAnswer)),
                        new ContextProjectionV2.RawMessage("m5", 5, "USER", current, sha(current))),
                List.of(), List.of(), List.of(), List.of(), 4096, 200, List.of(), "FULL");
        var frozen = new ContextV2ShadowSnapshotService.FrozenAnswer("snapshot", sha("snapshot"), projection);

        var context = new ConversationRetrievalContextAssembler().assembleV2("m5", current, frozen);

        assertThat(context.contextApplied()).isTrue();
        assertThat(context.topicAnchor()).contains("AlphaSpec");
        assertThat(context.retrievalQuestion()).contains("主题锚点：", "AlphaSpec");
    }

    @Test
    void countsFrozenWindowTurnsPerQuestionNotPerMessage() throws Exception {
        String first = "先介绍 AlphaSpec 的关键要求";
        String firstAnswer = "AlphaSpec 要求分阶段发布";
        String second = "把它分成两点讲";
        String secondAnswer = "第一点分阶段发布，第二点证据留痕";
        String current = "第二点为什么重要";
        var projection = new ContextProjectionV2("context-projection-v2", "context-window-v2-test",
                "workspace", "actor", "conversation", 5, current,
                List.of(new ContextProjectionV2.RawMessage("m1", 1, "USER", first, sha(first)),
                        new ContextProjectionV2.RawMessage("m2", 2, "ASSISTANT", firstAnswer, sha(firstAnswer)),
                        new ContextProjectionV2.RawMessage("m3", 3, "USER", second, sha(second)),
                        new ContextProjectionV2.RawMessage("m4", 4, "ASSISTANT", secondAnswer, sha(secondAnswer)),
                        new ContextProjectionV2.RawMessage("m5", 5, "USER", current, sha(current))),
                List.of(), List.of(), List.of(), List.of(), 4096, 200, List.of(), "FULL");
        var frozen = new ContextV2ShadowSnapshotService.FrozenAnswer("snapshot", sha("snapshot"), projection);

        var context = new ConversationRetrievalContextAssembler().assembleV2("m5", current, frozen);

        assertThat(context.windowTurnCount()).isEqualTo(2);
        assertThat(context.retrievalQuestion()).contains("连续原文窗口：");
    }

    @Test
    void leavesTopicAnchorBlankWhenTheFrozenWindowHasNoCompleteTurn() throws Exception {
        String onlyQuestion = "先介绍 AlphaSpec 的关键要求";
        String current = "第二点为什么重要";
        var projection = new ContextProjectionV2("context-projection-v2", "context-window-v2-test",
                "workspace", "actor", "conversation", 2, current,
                List.of(new ContextProjectionV2.RawMessage("m1", 1, "USER", onlyQuestion, sha(onlyQuestion)),
                        new ContextProjectionV2.RawMessage("m2", 2, "USER", current, sha(current))),
                List.of(), List.of(), List.of(), List.of(), 4096, 200, List.of(), "FULL");
        var frozen = new ContextV2ShadowSnapshotService.FrozenAnswer("snapshot", sha("snapshot"), projection);

        var context = new ConversationRetrievalContextAssembler().assembleV2("m2", current, frozen);

        assertThat(context.topicAnchor()).isEmpty();
        assertThat(context.retrievalQuestion()).doesNotContain("主题锚点：");
    }

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
