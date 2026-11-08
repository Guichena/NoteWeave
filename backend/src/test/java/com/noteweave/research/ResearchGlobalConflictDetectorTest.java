package com.noteweave.research;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResearchGlobalConflictDetectorTest {
    private final ResearchGlobalConflictDetector detector =
            new ResearchGlobalConflictDetector(new ResearchTypedClaimValidator());

    @Test
    void comparesEquivalentNamedDatesWithoutLosingLargeQuantityDifferences() {
        assertThat(detector.mutuallyExclusiveCodes(
                "On December 15, 2026 revenue was 300000",
                "On 15 December 2026 revenue was 300001")).containsExactly("NUMBER_VALUE_CONFLICT");
    }

    @Test
    void keepsSeparateMonthsAndDayPrecisionOutOfTheSameConflictContext() {
        assertThat(detector.mutuallyExclusiveCodes(
                "In February 2027 revenue was 300000", "In March 2027 revenue was 300001")).isEmpty();
        assertThat(detector.mutuallyExclusiveCodes(
                "On December 2026 revenue was 300000", "On December 15, 2026 revenue was 300001")).isEmpty();
    }
}
