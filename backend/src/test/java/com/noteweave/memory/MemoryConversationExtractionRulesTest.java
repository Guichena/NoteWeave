package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MemoryConversationExtractionRulesTest {

    @Test
    void onlyMessagesWithDurableCuesAreQueued() {
        assertThat(MemoryConversationExtractionService.shouldExtract("以后回答都用中文")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("记住：引用放在段落末尾")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("From now on, keep answers short")).isTrue();
        // 真实对话里常见的偏好说法
        assertThat(MemoryConversationExtractionService.shouldExtract("我比较喜欢用表格对比不同方案的优缺点")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("我一般习惯先看结论")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("我倾向于简短的回答")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("解释一下缓存一致性")).isFalse();
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
    void categoriesMapToCompileHintSlots() {
        assertThat(MemoryConversationExtractionService.categorize("以后统一称为检索增强生成")).isEqualTo("terminology");
        assertThat(MemoryConversationExtractionService.categorize("每次用表格对比")).isEqualTo("structure");
        assertThat(MemoryConversationExtractionService.categorize("以后回答语气正式一些")).isEqualTo("style");
    }
}
