package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryConversationExtractionRulesTest {

    @Test
    void messagesWithinLengthAreQueued() {
        assertThat(MemoryConversationExtractionService.shouldExtract("以后回答都用中文")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("记住：引用放在段落末尾")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("From now on, keep answers short")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("我比较喜欢用表格对比不同方案的优缺点")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("我一般习惯先看结论")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("我倾向于简短的回答")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("解释一下缓存一致性")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("太长了")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("  ")).isFalse();
    }

    @Test
    void ruleExtractionKeepsDirectiveSentencesAndSkipsQuestions() {
        var preferences = MemoryConversationExtractionService.ruleBased(
                "记住：以后回答先给结论再展开。另外，这篇论文的实验设置是什么？以后不要使用表情符号！");

        assertThat(preferences).extracting(MemoryConversationExtractionService.Preference::statement)
                .containsExactly("以后回答先给结论再展开", "以后不要使用表情符号");
        assertThat(preferences).extracting(MemoryConversationExtractionService.Preference::category)
                .containsExactly("structure", "forbidden");
        assertThat(preferences).allMatch(MemoryConversationExtractionService.Preference::explicit);
    }

    @Test
    void ruleFallbackExtractsImplicitFeedbackWithoutADurableCue() {
        var preferences = MemoryConversationExtractionService.ruleBased("太长了。别用英文。");

        assertThat(preferences).extracting(MemoryConversationExtractionService.Preference::statement)
                .containsExactly("太长了", "别用英文");
        assertThat(preferences).allMatch(MemoryConversationExtractionService.Preference::feedback);
        assertThat(preferences).noneMatch(MemoryConversationExtractionService.Preference::explicit);
        assertThat(preferences).extracting(item -> item.assignment().polarity())
                .containsExactly("FORBID", "FORBID");
        assertThat(preferences.get(0).assignment().slot()).isEqualTo("answer.length");
    }

    @Test
    void categoriesMapToCompileHintSlots() {
        assertThat(MemoryConversationExtractionService.categorize("以后统一称为检索增强生成")).isEqualTo("terminology");
        assertThat(MemoryConversationExtractionService.categorize("每次用表格对比")).isEqualTo("structure");
        assertThat(MemoryConversationExtractionService.categorize("以后回答语气正式一些")).isEqualTo("style");
    }

    @Test
    void executableLengthPreferenceIsNotNegativeFeedback() {
        String message = "以后回答不要太长";
        MemoryConversationExtractionService.Extraction extraction = new MemoryConversationExtractionService.Extraction(
                MemoryConversationExtractionService.ruleBased(message), "RULE");

        // 只抽成偏好，不打 feedback 标记
        assertThat(extraction.preferences()).singleElement()
                .satisfies(item -> assertThat(item.feedback()).isFalse());
        assertThat(MemoryConversationExtractionService.dissatisfied(extraction, message)).isFalse();
    }

    @Test
    void paraphrasedLengthPreferenceByModelIsNotNegativeFeedback() {
        String message = "以后回答不要太长";
        MemoryConversationExtractionService.Extraction extraction = new MemoryConversationExtractionService.Extraction(
                List.of(new MemoryConversationExtractionService.Preference("回答不要过长", "style", true)), "LLM");

        assertThat(MemoryConversationExtractionService.dissatisfied(extraction, message)).isFalse();
    }

    @Test
    void shortComplaintStillCountsAsNegativeFeedback() {
        String message = "太长了";
        MemoryConversationExtractionService.Extraction extracted = new MemoryConversationExtractionService.Extraction(
                MemoryConversationExtractionService.ruleBased(message), "RULE");
        assertThat(MemoryConversationExtractionService.dissatisfied(extracted, message)).isTrue();

        // 模型判定 NO、什么都没抽出来时，短句抱怨依然算负面
        MemoryConversationExtractionService.Extraction empty = new MemoryConversationExtractionService.Extraction(
                List.of(), "CLASSIFIED_NO");
        assertThat(MemoryConversationExtractionService.dissatisfied(empty, message)).isTrue();
        // 一条消息里同时有可执行偏好和短句抱怨，仍然算负面
        String mixed = "别用英文，太长了";
        MemoryConversationExtractionService.Extraction mixedExtraction =
                new MemoryConversationExtractionService.Extraction(List.of(), "LLM");
        assertThat(MemoryConversationExtractionService.dissatisfied(mixedExtraction, mixed)).isTrue();
    }

    @Test
    void longMessageIsNotNegativeFeedbackOnLengthAlone() {
        String message = "这篇论文的实验设置里有三个对照组，我们先把数据口径对齐，再讨论表格怎么画。"
                + "另外你刚才那段总结整体写得还行，只是有几个专有名词翻译得太长，需要统一一下叫法。";
        MemoryConversationExtractionService.Extraction extraction = new MemoryConversationExtractionService.Extraction(
                List.of(), "LLM");

        assertThat(message.length()).isGreaterThan(60);
        assertThat(MemoryConversationExtractionService.dissatisfied(extraction, message)).isFalse();
        assertThat(MemoryConversationExtractionService.dissatisfied(extraction, null)).isFalse();
    }
}
