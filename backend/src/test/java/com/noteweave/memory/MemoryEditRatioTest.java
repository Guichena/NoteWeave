package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MemoryEditRatioTest {

    @Test
    void identicalOrBlankTextHasNoEdit() {
        assertThat(MemoryEditRatio.ratio("同样的正文", "同样的正文")).isZero();
        assertThat(MemoryEditRatio.ratio("  同样的   正文 ", "同样的 正文")).isZero();
        assertThat(MemoryEditRatio.ratio("   ", "")).isZero();
        assertThat(MemoryEditRatio.ratio(null, null)).isZero();
    }

    @Test
    void blankAgainstTextIsAFullEdit() {
        assertThat(MemoryEditRatio.ratio("", "有内容")).isEqualTo(1);
        assertThat(MemoryEditRatio.ratio("有内容", " ")).isEqualTo(1);
    }

    @Test
    void ratioUsesTheLongerTextAsDenominator() {
        assertThat(MemoryEditRatio.ratio("aaaa", "aaab")).isEqualTo(0.25);
    }
}
