package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.conversation.ConversationContextProjectionService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F2-06 的离线部分：合成 30 轮长对话，记录上下文契约、预算与回忆覆盖的确定性统计。
 * 这里只做数据构造与离线契约统计，质量评分需要真实模型与标注数据，本期明确未完成。
 */
class ContextOfflineContractStatisticsTest {

    private final ConversationRetrievalContextAssembler assembler =
            new ConversationRetrievalContextAssembler();

    @Test
    void thirtyTurnSyntheticConversationProducesDeterministicBoundedContract() {
        var compilation = syntheticConversation(30);
        var projection = new ConversationContextProjectionService.Projection(List.of(), List.of());

        var first = assembler.assemble("workspace", "actor", "conversation", "m59",
                "第 12 点再展开一下", compilation, projection);
        var second = assembler.assemble("workspace", "actor", "conversation", "m59",
                "第 12 点再展开一下", compilation, projection);

        // 同一份冻结输入重复编译，契约、视图与 token 统计必须一致
        assertThat(first.contextContractSha256()).isEqualTo(second.contextContractSha256());
        assertThat(first.contextViewSha256()).isEqualTo(second.contextViewSha256());
        assertThat(first.contextEstimatedTokens()).isEqualTo(second.contextEstimatedTokens());
        // 最近一轮原文保留，较早回合退到冻结主题摘要
        assertThat(first.generationContext()).contains("第30轮回答");
        assertThat(first.generationContext()).contains("早期回合冻结摘要");
        assertThat(first.contextEstimatedTokens()).isLessThanOrEqualTo(5_592);
        // 合成数据明确标记为契约统计，不冒充真实用户流量或质量评分
        assertThat(first.contextDegradations())
                .anyMatch(reason -> reason.contains("EARLIER_TURNS_USE_TOPIC_SUMMARY")
                        || reason.contains("RAW_")
                        || reason.contains("SUMMARY_"));
    }

    @Test
    void anotherConversationDoesNotLeakIntoTheFrozenView() {
        var compilation = syntheticConversation(3);
        var projection = new ConversationContextProjectionService.Projection(List.of(), List.of());

        var first = assembler.assemble("workspace", "actor", "conversation-a", "m5",
                "继续", compilation, projection);
        var second = assembler.assemble("workspace", "actor", "conversation-b", "m5",
                "继续", compilation, projection);

        // 输入只来自各自的冻结投影；不同会话身份不改变材料选择，也不会互相注入
        assertThat(first.contextEstimatedTokens()).isEqualTo(second.contextEstimatedTokens());
        assertThat(first.generationContext()).doesNotContain("另一个会话");
    }

    private ConversationContextProjectionService.CompilationProjection syntheticConversation(int turns) {
        List<ConversationContextProjectionService.RawMessage> messages = new ArrayList<>();
        int seq = 1;
        for (int turn = 1; turn <= turns; turn++) {
            messages.add(new ConversationContextProjectionService.RawMessage(
                    "m" + seq, seq, "USER", "第" + turn + "轮问题：请说明要点 " + "背景".repeat(20),
                    "hash-u" + seq));
            seq++;
            messages.add(new ConversationContextProjectionService.RawMessage(
                    "m" + seq, seq, "ASSISTANT", "第" + turn + "轮回答：" + "结论".repeat(30),
                    "hash-a" + seq));
            seq++;
        }
        return new ConversationContextProjectionService.CompilationProjection(
                new ConversationContextProjectionService.SegmentSummaryRef(
                        "segment-1", "summary-1", 1, Math.max(2, seq - 21), "hash-summary"),
                "早期回合冻结摘要",
                messages);
    }
}
