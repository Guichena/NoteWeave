package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.chat.ChatLlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ConversationSummaryGeneratorTest {

    @Test
    void llmUpdatesThePreviousSummaryWithOnlyTheNewTurns() {
        RecordingLlm llm = new RecordingLlm("- 用户在比较两种缓存写入顺序\n- 已确认先写数据库再删缓存");
        ConversationSummaryGenerator generator = new ConversationSummaryGenerator(llm);

        ConversationSummaryGenerator.Summary summary = generator.summarize("- 用户在比较两种缓存写入顺序",
                List.of(new ConversationSummaryGenerator.Turn("USER", "那先删缓存呢？"),
                        new ConversationSummaryGenerator.Turn("ASSISTANT", "先删缓存会出现并发回填旧值。")));

        assertThat(summary.method()).isEqualTo(ConversationSummaryGenerator.METHOD_LLM_INCREMENTAL);
        assertThat(summary.text()).contains("已确认先写数据库再删缓存");
        // 提示词里只带上一版摘要和新增的两条消息
        assertThat(llm.userPrompts).singleElement().satisfies(prompt -> {
            assertThat(prompt).contains("已有摘要：\n- 用户在比较两种缓存写入顺序");
            assertThat(prompt).contains("用户：那先删缓存呢？").contains("助手：先删缓存会出现并发回填旧值。");
        });
    }

    @Test
    void firstSummaryWithoutBaseIsMarkedAsFullSummary() {
        ConversationSummaryGenerator generator = new ConversationSummaryGenerator(new RecordingLlm("摘要"));

        ConversationSummaryGenerator.Summary summary = generator.summarize(null,
                List.of(new ConversationSummaryGenerator.Turn("USER", "第一个问题")));

        assertThat(summary.method()).isEqualTo(ConversationSummaryGenerator.METHOD_LLM_FULL);
    }

    @Test
    void failedOrEmptyModelOutputFallsBackToExtractiveSummary() {
        List<ConversationSummaryGenerator.Turn> turns = List.of(
                new ConversationSummaryGenerator.Turn("USER", "新问题"),
                new ConversationSummaryGenerator.Turn("ASSISTANT", "新回答"));

        ConversationSummaryGenerator.Summary failed = new ConversationSummaryGenerator(new RecordingLlm(null))
                .summarize("旧摘要", turns);
        ConversationSummaryGenerator.Summary empty = new ConversationSummaryGenerator(new RecordingLlm("  "))
                .summarize("旧摘要", turns);
        ConversationSummaryGenerator.Summary disabled = ConversationSummaryGenerator.extractiveOnly()
                .summarize("旧摘要", turns);

        for (ConversationSummaryGenerator.Summary summary : List.of(failed, empty, disabled)) {
            assertThat(summary.method()).isEqualTo(ConversationSummaryGenerator.METHOD_EXTRACTIVE);
            assertThat(summary.text()).isEqualTo("旧摘要\nuser: 新问题\nassistant: 新回答");
        }
    }

    @Test
    void tokenEstimateCountsCjkPerCharacterAndOtherTextPerFourCharacters() {
        assertThat(ContextTokenEstimator.estimate(null)).isZero();
        assertThat(ContextTokenEstimator.estimate("缓存一致性")).isEqualTo(5);
        assertThat(ContextTokenEstimator.estimate("cache")).isEqualTo(2);
        assertThat(ContextTokenEstimator.estimate("先写 DB")).isEqualTo(3);
        // 中文按字节计算会是字数的 3 倍，估算的 token 数明显更接近实际
        assertThat(ContextTokenEstimator.estimate("缓存一致性"))
                .isLessThan("缓存一致性".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    private static final class RecordingLlm implements ChatLlmClient {
        private final String output;
        private final List<String> userPrompts = new ArrayList<>();

        private RecordingLlm(String output) {
            this.output = output;
        }

        @Override
        public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                 Consumer<String> onToken) {
            userPrompts.add(userPrompt);
            if (output == null) throw new IllegalStateException("model unavailable");
            return output;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
