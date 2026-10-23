package com.noteweave.conversation.constraint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConstraintSlotMappingTest {

    @Test
    void everySlotMapsToTheStableInternalSlot() {
        assertThat(ConstraintSlot.LANGUAGE.internalSlot()).isEqualTo("answer.language");
        assertThat(ConstraintSlot.TABLE.internalSlot()).isEqualTo("format.table");
        assertThat(ConstraintSlot.CONCLUSION.internalSlot()).isEqualTo("format.conclusion");
        assertThat(ConstraintSlot.BULLETS.internalSlot()).isEqualTo("format.bullets");
        assertThat(ConstraintSlot.LENGTH_LIMIT.internalSlot()).isEqualTo("format.length.max");
        assertThat(ConstraintSlot.DETAIL.internalSlot()).isEqualTo("format.length.detail");
        assertThat(ConstraintSlot.CITATION.internalSlot()).isEqualTo("format.citation");
        assertThat(ConstraintSlot.CODE.internalSlot()).isEqualTo("format.code");
        assertThat(ConstraintSlot.NEGATION.internalSlot()).isEqualTo("negation");
    }

    @Test
    void dbKindStaysWithinTheThreePersistedValues() {
        assertThat(ConstraintSlot.LANGUAGE.dbKind()).isEqualTo("LANGUAGE");
        assertThat(ConstraintSlot.NEGATION.dbKind()).isEqualTo("NEGATION");
        for (ConstraintSlot slot : ConstraintSlot.values()) {
            assertThat(slot.dbKind()).isIn("LANGUAGE", "FORMAT", "NEGATION");
        }
    }

    @Test
    void wireValuesRoundTripAndTolerateUnderscores() {
        for (ConstraintSlot slot : ConstraintSlot.values()) {
            assertThat(ConstraintSlot.fromWire(slot.wire())).isEqualTo(slot);
        }
        assertThat(ConstraintSlot.fromWire("LENGTH_LIMIT")).isEqualTo(ConstraintSlot.LENGTH_LIMIT);
        assertThat(ConstraintSlot.fromWire("  Language ")).isEqualTo(ConstraintSlot.LANGUAGE);
    }

    @Test
    void unknownWireValuesAreRejectedInsteadOfGuessed() {
        assertThat(ConstraintSlot.fromWire("answer.language")).isNull();
        assertThat(ConstraintSlot.fromWire("length")).isNull();
        assertThat(ConstraintSlot.fromWire("")).isNull();
        assertThat(ConstraintSlot.fromWire(null)).isNull();
    }

    @Test
    void forbidTextIsReadableInsteadOfAnInternalSlotName() {
        // 禁止项要直接落成回答提示词可读的句子，不能写 format.table 这种内部槽位名。
        assertThat(ConstraintSlot.TABLE.forbidText("format.table")).isEqualTo("不要使用表格");
        assertThat(ConstraintSlot.LANGUAGE.forbidText("中文")).isEqualTo("不要使用中文");
        assertThat(ConstraintSlot.BULLETS.forbidText(null)).isEqualTo("不要使用分点");
        assertThat(ConstraintSlot.NEGATION.forbidText(null)).isEqualTo("不要这样做");
        assertThat(ConstraintSlot.NEGATION.forbidText("大段配置")).isEqualTo("不要大段配置");
        // 模型给了可读取值时保留它，不能固定成相反的意思。
        assertThat(ConstraintSlot.DETAIL.forbidText("简短")).isEqualTo("不要简短");
        assertThat(ConstraintSlot.LENGTH_LIMIT.forbidText("100字")).isEqualTo("不要限制为100字");
        // 取值本身已是完整否定句时不再叠一层。
        assertThat(ConstraintSlot.DETAIL.forbidText("不要展开")).isEqualTo("不要展开");
        assertThat(ConstraintSlot.LENGTH_LIMIT.forbidText("不要太长")).isEqualTo("不要太长");
    }

    @Test
    void polarityWireValuesRoundTripAndRejectUnknownOnes() {
        for (ConstraintPolarity polarity : ConstraintPolarity.values()) {
            assertThat(ConstraintPolarity.fromWire(polarity.wire())).isEqualTo(polarity);
        }
        assertThat(ConstraintPolarity.fromWire("FORBID")).isEqualTo(ConstraintPolarity.FORBID);
        assertThat(ConstraintPolarity.fromWire(" require ")).isEqualTo(ConstraintPolarity.REQUIRE);
        assertThat(ConstraintPolarity.fromWire("maybe")).isNull();
        assertThat(ConstraintPolarity.fromWire(null)).isNull();
    }

    @Test
    void thisTurnFoldsIntoCurrentTopicBecauseThePlannerOnlyKnowsTwoScopes() {
        assertThat(ConstraintScope.THIS_TURN.dbScope()).isEqualTo("CURRENT_TOPIC");
        assertThat(ConstraintScope.CURRENT_TOPIC.dbScope()).isEqualTo("CURRENT_TOPIC");
        assertThat(ConstraintScope.CONVERSATION.dbScope()).isEqualTo("CONVERSATION");
        assertThat(ConstraintScope.fromWire("this_turn")).isEqualTo(ConstraintScope.THIS_TURN);
        assertThat(ConstraintScope.fromWire("everywhere")).isNull();
    }
}
