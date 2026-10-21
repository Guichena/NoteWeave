package com.noteweave.conversation.constraint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConstraintOpTest {

    @Test
    void valueIsTrimmedAndBlankBecomesNull() {
        assertThat(new ConstraintOp(ConstraintOp.Op.ADD, ConstraintSlot.TABLE, "  用表格  ", null, null, 0.8)
                .value()).isEqualTo("用表格");
        assertThat(new ConstraintOp(ConstraintOp.Op.NOOP, null, "   ", null, null, 0.8).value()).isNull();
        assertThat(new ConstraintOp(ConstraintOp.Op.REVOKE, ConstraintSlot.TABLE, null, null, "  ", 0.8)
                .targetId()).isNull();
    }

    @Test
    void valueLongerThanTheCapIsRejectedByTheRecordToo() {
        String tooLong = "中".repeat(ConstraintOp.MAX_VALUE_CHARS + 1);

        assertThatThrownBy(() -> new ConstraintOp(ConstraintOp.Op.ADD, ConstraintSlot.LANGUAGE, tooLong, null, null, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ConstraintOp(ConstraintOp.Op.ADD, ConstraintSlot.LANGUAGE,
                "中".repeat(ConstraintOp.MAX_VALUE_CHARS), null, null, 0.8).value())
                .hasSize(ConstraintOp.MAX_VALUE_CHARS);
    }

    @Test
    void confidenceMustBeFiniteAndInsideTheUnitInterval() {
        assertThatThrownBy(() -> new ConstraintOp(ConstraintOp.Op.NOOP, null, null, null, null, 1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConstraintOp(ConstraintOp.Op.NOOP, null, null, null, null, -0.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConstraintOp(ConstraintOp.Op.NOOP, null, null, null, null, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void opIsMandatoryAndDrivesTheRequiredFields() {
        assertThatThrownBy(() -> new ConstraintOp(null, null, null, null, null, 0.8))
                .isInstanceOf(NullPointerException.class);
        assertThat(new ConstraintOp(ConstraintOp.Op.ADD, ConstraintSlot.TABLE, null, null, null, 0.8).requiresSlot())
                .isTrue();
        assertThat(new ConstraintOp(ConstraintOp.Op.UPDATE, ConstraintSlot.TABLE, null, null, "c-1", 0.8).requiresSlot())
                .isTrue();
        assertThat(new ConstraintOp(ConstraintOp.Op.NOOP, null, null, null, null, 0.8).requiresSlot()).isFalse();
        assertThat(new ConstraintOp(ConstraintOp.Op.REVOKE, null, null, null, null, 0.8).requiresTarget()).isTrue();
        assertThat(new ConstraintOp(ConstraintOp.Op.REVOKE, null, null, null, null, 0.8).writesRow()).isFalse();
    }
}
