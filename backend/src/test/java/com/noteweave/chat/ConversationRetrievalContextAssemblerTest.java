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
                "workspace", "actor", "conversation", "m3", "继续展开这个问题", compilation, projection);

        assertThat(result.contextApplied()).isTrue();
        assertThat(result.windowTurnCount()).isEqualTo(1);
        assertThat(result.retrievalQuestion())
                .contains("当前问题")
                .contains("如何设计检索降级策略？")
                .contains("建议 默认应 fail closed")
                .contains("既有主题摘要");
        assertThat(result.generationContext()).isNotBlank()
                .contains("如何设计检索降级策略？")
                .contains("建议 默认应 fail closed");
        assertThat(result.contextContractSha256()).isNotBlank();
        assertThat(result.contextViewSha256()).isNotBlank();
        assertThat(result.contextEstimatedTokens()).isPositive();
        assertThat(result.inputProjection()).isSameAs(projection);
    }

    @Test
    void keepsContentBeyondOldCharacterBudgetsForRewriteAndGeneration() {
        String marker = "第二点关键结论在旧截断之后仍然可见";
        String longAnswer = "背景说明".repeat(90) + marker;
        ConversationContextProjectionService.CompilationProjection compilation =
                new ConversationContextProjectionService.CompilationProjection(
                        null,
                        "",
                        List.of(
                                raw("m1", 1, "USER", "请解释 AlphaSpec 的两个要点"),
                                raw("m2", 2, "ASSISTANT", longAnswer),
                                raw("m3", 3, "USER", "把第一点再展开")
                        )
                );
        ConversationContextProjectionService.Projection projection =
                new ConversationContextProjectionService.Projection(List.of(), List.of());

        ConversationRetrievalContextAssembler.Context result = assembler.assemble(
                "workspace", "actor", "conversation", "m3", "把第一点再展开", compilation, projection);

        // 旧实现只保留原文 180 字，这里要求改写与生成都能看到 180 字之后的内容
        assertThat(result.retrievalQuestion()).contains(marker);
        assertThat(result.generationContext()).contains(marker);
    }

    @Test
    void overBudgetRawHistoryIsDroppedWithExplicitDegradation() {
        ConversationContextProjectionService.CompilationProjection compilation =
                new ConversationContextProjectionService.CompilationProjection(
                        null,
                        "",
                        List.of(
                                raw("m1", 1, "USER", "前面在讨论 AlphaSpec"),
                                raw("m2", 2, "ASSISTANT", "甲".repeat(900)),
                                raw("m3", 3, "USER", "继续展开")
                        )
                );
        ConversationContextProjectionService.Projection projection =
                new ConversationContextProjectionService.Projection(List.of(), List.of());

        // 硬上限 1_200 - 500 - 200 = 500 token；长助手回答放不下，必须记录剔除原因而不是截字
        ConversationRetrievalContextAssembler.Context result = assembler.assembleWithBudget(
                "workspace", "actor", "conversation", "m3", "继续展开",
                compilation, projection, 1_200, 500);

        assertThat(result.contextDegradations()).contains("CONTEXT_RAW_MESSAGE_DROPPED");
        assertThat(result.generationContext()).doesNotContain("甲甲甲甲");
        assertThat(result.contextEstimatedTokens()).isLessThanOrEqualTo(500);
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
