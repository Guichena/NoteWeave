package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 槽位只由模型输出决定：这里只校验「模型给的槽位被规范化保留」和「模型没给合法槽位时按模型给的 category 兜底」。
 * 语句里的关键词（中文、表格、简短……）不再参与槽位推断，相关断言已随关键词推断一起删除。
 */
class MemorySlotsTest {

    @ParameterizedTest(name = "模型给出的「{0}」原样保留")
    @ValueSource(strings = {
            "answer.language",
            "answer.length",
            "answer.style",
            "answer.constraint",
            "format.structure",
            "format.table",
            "terminology.term",
            "interaction.style"
    })
    void modelProvidedSlotIsKeptAsIs(String slot) {
        MemorySlots.Assignment assignment = MemorySlots.complete(
                "style", "回答语气正式一些", "PREFERENCE", slot, "正式", "REQUIRE");

        assertThat(assignment.slot()).isEqualTo(slot);
    }

    @ParameterizedTest(name = "模型给出的「{0}」规范化为「{1}」")
    @CsvSource({
            "Answer.Length, answer.length",
            "' Format.Table ', format.table",
            "TERMINOLOGY.TERM, terminology.term"
    })
    void modelSlotIsNormalized(String slot, String expectedSlot) {
        assertThat(MemorySlots.complete("style", "回答语气正式一些", "PREFERENCE", slot, "正式", "REQUIRE").slot())
                .isEqualTo(expectedSlot);
    }

    @Test
    void slotKeywordsInTheStatementNoLongerDecideTheSlot() {
        // 「中文」「表格」「简短」以前会各自推出 answer.language / format.table / answer.length
        assertThat(complete("style", "以后回答都用中文").slot()).isEqualTo("answer.style");
        assertThat(complete("style", "以后用表格对比不同方案").slot()).isEqualTo("answer.style");
        assertThat(complete("style", "回答简短一点").slot()).isEqualTo("answer.style");
        // 模型给的槽位与语句措辞冲突时，以模型为准
        assertThat(MemorySlots.complete("style", "以后回答都用中文", "PREFERENCE",
                "answer.length", "不要太长", "REQUIRE").slot()).isEqualTo("answer.length");
    }

    @Test
    void missingOrInvalidSlotFallsBackToTheModelCategory() {
        assertThat(complete("style", "以后回答都用中文").slot()).isEqualTo("answer.style");
        assertThat(complete("structure", "以后回答先给结论再展开").slot()).isEqualTo("format.structure");
        assertThat(complete("forbidden", "以后不要使用表情符号").slot()).isEqualTo("answer.constraint");
        assertThat(complete("terminology", "以后统一称为检索增强生成").slot()).isEqualTo("terminology.term");
        assertThat(complete("interaction", "回答前先追问确认需求").slot()).isEqualTo("interaction.style");
        // 不合规范的值当作没给
        assertThat(complete("structure", "以后回答先给结论再展开", "不是槽位").slot())
                .isEqualTo("format.structure");
        assertThat(complete("forbidden", "以后不要使用表情符号", "answer").slot())
                .isEqualTo("answer.constraint");
    }

    @Test
    void everyAllowedSlotIsWellFormedAndCoversTheCategoryFallback() {
        assertThat(MemorySlots.ALLOWED_SLOTS).hasSize(8).doesNotHaveDuplicates();
        for (String slot : MemorySlots.ALLOWED_SLOTS) {
            assertThat(MemorySlots.normalizeSlot(slot)).isEqualTo(slot);
        }
        // 兜底槽位也在枚举内，避免模型留空时写出枚举外的槽位
        assertThat(MemorySlots.ALLOWED_SLOTS).contains(
                MemorySlots.slotForCategory("style"),
                MemorySlots.slotForCategory("forbidden"),
                MemorySlots.slotForCategory("terminology"),
                MemorySlots.slotForCategory("structure"),
                MemorySlots.slotForCategory("interaction"));
    }

    @Test
    void valueAndPolarityStillFollowTheExistingRules() {
        MemorySlots.Assignment assignment = MemorySlots.complete(
                "forbidden", "以后不要使用表情符号", "PREFERENCE", "answer.constraint", null, null);

        assertThat(assignment.value()).isEqualTo("以后不要使用表情符号");
        assertThat(assignment.polarity()).isEqualTo("FORBID");
    }

    @Test
    void publicSlotStillHidesInternalKeys() {
        assertThat(MemorySlots.publicSlot(null)).isEqualTo("unknown");
        assertThat(MemorySlots.publicSlot("legacy:answer.length")).isEqualTo("unknown");
        assertThat(MemorySlots.publicSlot("Answer.Length")).isEqualTo("answer.length");
        assertThat(MemorySlots.publicSlot("不是槽位")).isEqualTo("unknown");
    }

    private static MemorySlots.Assignment complete(String category, String statement) {
        return MemorySlots.complete(category, statement, "PREFERENCE", null, null, null);
    }

    private static MemorySlots.Assignment complete(String category, String statement, String slot) {
        return MemorySlots.complete(category, statement, "PREFERENCE", slot, null, null);
    }
}
