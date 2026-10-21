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

    @Test
    void longMessageIsSplitIntoChunksInThePromptInsteadOfBeingTruncated() {
        String content = "开头" + "中".repeat(4_496) + "结尾";
        RecordingLlm llm = new RecordingLlm("摘要");

        new ConversationSummaryGenerator(llm).summarize(null,
                List.of(new ConversationSummaryGenerator.Turn("USER", content)));

        String prompt = llm.userPrompts.get(0);
        // 4500 字按 2000 字分块，共 3 块，块间有续块标记
        assertThat(prompt).contains("（分块续 2/3）").contains("（分块续 3/3）");
        // 去掉续块标记后，原文完整地进了提示词，尾部没有被丢掉
        String flattened = prompt.replaceAll("\n（分块续 \\d+/\\d+）\n", "");
        assertThat(flattened).contains("用户：" + content);
        assertThat(prompt).doesNotContain(ConversationSummaryGenerator.TRUNCATION_MARKER);
    }

    @Test
    void extractiveMessageKeepsHeadAndTailAndMarksTheTruncation() {
        String content = "开头" + "中".repeat(1_000) + "结尾";

        String line = ConversationSummaryGenerator.summarizeMessage("USER", content);
        assertThat(line).startsWith("user: 开头").endsWith("结尾")
                .contains(ConversationSummaryGenerator.TRUNCATION_MARKER);
        // 标记计入 600 字额度
        assertThat(line.length() - "user: ".length()).isLessThanOrEqualTo(600);

        ConversationSummaryGenerator.Summary summary = ConversationSummaryGenerator.extractiveOnly()
                .summarize(null, List.of(new ConversationSummaryGenerator.Turn("ASSISTANT", content)));
        assertThat(summary.method()).isEqualTo(ConversationSummaryGenerator.METHOD_EXTRACTIVE);
        assertThat(summary.text()).contains(ConversationSummaryGenerator.TRUNCATION_MARKER)
                .endsWith("结尾");
    }

    @Test
    void extractiveSummaryOverTheTotalLimitKeepsHeadAndTailAndMarksTheTruncation() {
        List<ConversationSummaryGenerator.Turn> turns = new ArrayList<>();
        turns.add(new ConversationSummaryGenerator.Turn("USER", "第0条：" + "填".repeat(580)));
        for (int index = 1; index < 30; index++) {
            turns.add(new ConversationSummaryGenerator.Turn("USER", "第" + index + "条：" + "填".repeat(580)));
        }
        turns.add(new ConversationSummaryGenerator.Turn("USER", "最后一条：" + "末".repeat(580)));

        String text = ConversationSummaryGenerator.extractiveOnly().summarize(null, turns).text();

        assertThat(text.length()).isLessThanOrEqualTo(ConversationSummaryGenerator.MAX_SUMMARY_CHARS);
        assertThat(text).startsWith("user: 第0条：").endsWith("末".repeat(580));
        // 单条都没超 600 字，标记只能来自总量截断，且只出现一次
        assertThat(text.indexOf(ConversationSummaryGenerator.TRUNCATION_MARKER))
                .isEqualTo(text.lastIndexOf(ConversationSummaryGenerator.TRUNCATION_MARKER))
                .isGreaterThan(0);
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
