package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.conversation.ConversationContextProjectionService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * F2-06 的长对话质量评测：30 轮合成对话里放两个唯一事实（早期事实只存在于冻结摘要，
 * 近期事实在原文窗口），让真实模型基于压缩后的生成上下文回答，按事实召回率计分。
 * 需要 DEEPSEEK_API_KEY 非空且 -Dnoteweave.live.llm=true。
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "noteweave.live.llm", matches = "true")
class ContextLongConversationLiveEvalTest {

    private final ConversationRetrievalContextAssembler assembler =
            new ConversationRetrievalContextAssembler();

    @Test
    void liveModelRecallsFactsAcrossCompressedThirtyTurnConversation() {
        ChatLlmClient client = LiveLlmEvalSupport.client();
        System.out.println("[context long live] " + LiveLlmEvalSupport.describeTarget());
        int checks = 0;
        int passed = 0;
        for (int index = 1; index <= 20; index++) {
            String earlyCode = "ORION-" + (7300 + index);
            String lateCode = "VEGA-" + (9100 + index);
            var compilation = syntheticConversation(earlyCode, lateCode);
            var projection = new ConversationContextProjectionService.Projection(List.of(), List.of());
            var context = assembler.assemble("w", "actor", "conversation-" + index, "m60",
                    "项目代号和最新批次号分别是什么？只回答两个编号", compilation, projection);

            String answer = client.streamChat(
                    "你是严谨的问答助手，只根据给定上下文回答。",
                    context.generationContext() + "\n\n问题：项目代号和最新批次号分别是什么？只回答两个编号。",
                    200, token -> { });
            checks += 2;
            if (answer.contains(earlyCode)) {
                passed++;
            } else {
                System.out.println("[context long live] early fact missed in case " + index + ": " + answer);
            }
            if (answer.contains(lateCode)) {
                passed++;
            } else {
                System.out.println("[context long live] late fact missed in case " + index + ": " + answer);
            }
        }
        System.out.println(LiveLlmEvalSupport.scoreLine("context long live eval", passed, checks));
        assertThat(passed).as("长对话事实召回率至少 0.8").isGreaterThanOrEqualTo((int) Math.ceil(checks * 0.8));
    }

    private ConversationContextProjectionService.CompilationProjection syntheticConversation(
            String earlyCode, String lateCode) {
        List<ConversationContextProjectionService.RawMessage> messages = new ArrayList<>();
        int seq = 1;
        for (int turn = 1; turn <= 30; turn++) {
            String user = turn == 2
                    ? "请记住项目代号是 " + earlyCode + "。"
                    : turn == 29 ? "最新批次号是 " + lateCode + "。" : "第 " + turn + " 轮问题：继续讨论部署与容量。";
            messages.add(new ConversationContextProjectionService.RawMessage(
                    "m" + seq, seq, "USER", user, "hash-u" + seq));
            seq++;
            messages.add(new ConversationContextProjectionService.RawMessage(
                    "m" + seq, seq, "ASSISTANT", "第 " + turn + " 轮回答：已记录。", "hash-a" + seq));
            seq++;
        }
        return new ConversationContextProjectionService.CompilationProjection(
                new ConversationContextProjectionService.SegmentSummaryRef(
                        "segment-1", "summary-1", 1, 40, "hash-summary"),
                "早期回合冻结摘要：项目代号是 " + earlyCode + "。",
                messages);
    }
}
