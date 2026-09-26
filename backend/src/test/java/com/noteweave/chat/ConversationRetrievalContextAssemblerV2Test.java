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

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
