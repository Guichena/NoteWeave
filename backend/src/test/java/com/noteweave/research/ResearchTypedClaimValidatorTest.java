package com.noteweave.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchTypedClaimValidatorTest {

    private final ResearchTypedClaimValidator validator = new ResearchTypedClaimValidator();

    @Test
    void rejectsNumberUnitDateAndDirectionContradictions() {
        ResearchTypedClaimValidator.Validation validation = validator.validate(
                "Latency decreased below 50 ms after 2025-01-01",
                "Latency increased above 50 s after 2024-01-01");

        assertThat(validation.status()).isEqualTo("CONTRADICTED");
        assertThat(validation.reasonCodes()).contains(
                "UNIT_CONTRADICTION", "DATE_VALUE_CONTRADICTION",
                "COMPARISON_DIRECTION_CONTRADICTION");
    }

    @Test
    void missingQuantitativeFactCannotPassByLexicalOverlap() {
        ResearchTypedClaimValidator.Validation validation = validator.validate(
                "The completion rate is 15%", "The completion rate is reported");

        assertThat(validation.status()).isEqualTo("UNKNOWN");
        assertThat(validation.reasonCodes()).containsExactly("TYPED_FACT_MISSING_FROM_CITATION_SPAN");
    }

    @Test
    void matchingChineseFactsAreEntailed() {
        ResearchTypedClaimValidator.Validation validation = validator.validate(
                "2026年8月7日延迟下降到至少50毫秒",
                "报告显示2026年8月7日延迟下降到至少50毫秒");

        assertThat(validation.status()).isEqualTo("ENTAILED");
    }
}
