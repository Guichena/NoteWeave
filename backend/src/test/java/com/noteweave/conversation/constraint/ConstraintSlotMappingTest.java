package com.noteweave.conversation.constraint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConstraintSlotMappingTest {

    @Test
    void everySlotMapsToTheSameInternalSlotAsTheRulePath() {
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
    void thisTurnFoldsIntoCurrentTopicBecauseThePlannerOnlyKnowsTwoScopes() {
        assertThat(ConstraintScope.THIS_TURN.dbScope()).isEqualTo("CURRENT_TOPIC");
        assertThat(ConstraintScope.CURRENT_TOPIC.dbScope()).isEqualTo("CURRENT_TOPIC");
        assertThat(ConstraintScope.CONVERSATION.dbScope()).isEqualTo("CONVERSATION");
        assertThat(ConstraintScope.fromWire("this_turn")).isEqualTo(ConstraintScope.THIS_TURN);
        assertThat(ConstraintScope.fromWire("everywhere")).isNull();
    }
}
