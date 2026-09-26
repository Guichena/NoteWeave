package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.conversation.ConversationContextProjectionService;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationRetrievalContextAssemblerTest {

    private final ConversationRetrievalContextAssembler assembler = new ConversationRetrievalContextAssembler();

    @Test
    void assemblesRecentTurnForFollowUpQuestion() {
        ConversationContextProjectionService.CompilationProjection compilation =
                new ConversationContextProjectionService.CompilationProjection(
                        null,
                        "既有主题摘要",
                        List.of(
                                raw("m1", 1, "USER", "如何设计检索降级策略？"),
                                raw("m2", 2, "ASSISTANT", "## 建议\n- 默认应 fail closed"),
                                raw("m3", 3, "USER", "继续展开这个问题")
                        )
                );
        ConversationContextProjectionService.Projection projection =
                new ConversationContextProjectionService.Projection(List.of(), List.of());

        ConversationRetrievalContextAssembler.Context result = assembler.assemble(
                "m3", "继续展开这个问题", compilation, projection);

        assertThat(result.contextApplied()).isTrue();
        assertThat(result.windowTurnCount()).isEqualTo(1);
        assertThat(result.retrievalQuestion())
                .contains("当前问题：继续展开这个问题")
                .contains("用户：如何设计检索降级策略？")
                .contains("助手摘要：建议 默认应 fail closed")
                .contains("前序主题摘要：既有主题摘要");
        assertThat(result.inputProjection()).isSameAs(projection);
    }

    @Test
    void independentQuestionDoesNotRequireHistory() {
        assertThat(assembler.requiresHistory("请系统解释事件溯源架构的适用边界"))
                .isFalse();
        assertThat(assembler.empty(" 独立问题 ").contextApplied()).isFalse();
    }

    @Test
    void classifiesQuestionIntentWithoutAnswerOrRetrievalDependencies() {
        assertThat(assembler.classifyQuestion("比较两个方案的区别")).isEqualTo("comparison");
        assertThat(assembler.classifyQuestion("为什么会失败")).isEqualTo("reasoning");
        assertThat(assembler.classifyQuestion("给出引用来源")).isEqualTo("source_lookup");
    }

    private ConversationContextProjectionService.RawMessage raw(
            String id, int seq, String role, String content) {
        return new ConversationContextProjectionService.RawMessage(id, seq, role, content, "hash-" + id);
    }
}
