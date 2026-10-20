package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 槽位推断：长度词表覆盖详略、字数、篇幅和长一点/短一点这类说法。 */
class MemorySlotsTest {

    @ParameterizedTest(name = "「{0}」归入长度槽")
    @ValueSource(strings = {
            "回答别太啰嗦",
            "请精简回答",
            "希望回答更详细",
            "控制字数在三百字以内",
            "篇幅尽量收窄",
            "回答再长一点",
            "回答短一点"
    })
    void lengthVocabularyMapsToLengthSlot(String statement) {
        MemorySlots.Assignment assignment = complete(statement);

        assertThat(assignment.slot()).isEqualTo("answer.length");
    }

    @ParameterizedTest(name = "「{0}」仍归入 {1}")
    @CsvSource({
            "回答简短一些, answer.length",
            "太长了, answer.length",
            "回答用简洁的段落, answer.length",
            "用户可能偏好更长的解释, answer.style",
            "回答语气正式一些, answer.style",
            "以后回答先给结论再展开, format.structure",
            "回答使用英文, answer.language",
            "用表格对比不同方案, format.table",
            "以后不要使用表情符号, answer.constraint",
            "以后统一称为检索增强生成, terminology.term",
            "回答前先追问确认需求, interaction.style"
    })
    void slotInferenceKeepsExistingVocabulary(String statement, String expectedSlot) {
        assertThat(complete(statement).slot()).isEqualTo(expectedSlot);
    }

    @Test
    void languageAndLengthPriorityIsStable() {
        // 语言词先于长度词判定：同时出现时仍归入语言槽
        assertThat(complete("用英文简短回答").slot()).isEqualTo("answer.language");
    }

    @Test
    void lengthWordsDoNotChangeCategory() {
        // 只影响槽位推断，类别判定不变：啰嗦属于表达风格信号
        assertThat(MemorySlots.categoryOf("回答别太啰嗦")).isEqualTo("forbidden");
        assertThat(MemorySlots.categoryOf("希望回答更详细")).isEqualTo("style");
    }

    @Test
    void explicitSlotStillWinsOverLengthInference() {
        MemorySlots.Assignment assignment = MemorySlots.complete(
                MemorySlots.categoryOf("希望回答更详细"), "希望回答更详细", "PREFERENCE",
                "answer.style", "详细一点", "REQUIRE");

        assertThat(assignment.slot()).isEqualTo("answer.style");
        assertThat(assignment.value()).isEqualTo("详细一点");
        assertThat(assignment.polarity()).isEqualTo("REQUIRE");
    }

    private static MemorySlots.Assignment complete(String statement) {
        return MemorySlots.complete(
                MemorySlots.categoryOf(statement), statement, "PREFERENCE", null, null, null);
    }
}
