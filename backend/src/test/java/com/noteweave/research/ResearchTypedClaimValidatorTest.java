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

    @Test
    void matchesTranslatedMonthWithoutTreatingDatePartsAsQuantities() {
        String quote = "Phase two: 600 teachers in February 2027.";
        assertThat(validator.validate("第二阶段计划为2027年2月覆盖600名教师", quote).status()).isEqualTo("ENTAILED");
        assertThat(validator.validate("第二阶段计划为2027年3月覆盖600名教师", quote).status()).isEqualTo("CONTRADICTED");
        assertThat(validator.validate("第二阶段计划为2027年2月覆盖601名教师", quote).status()).isEqualTo("CONTRADICTED");
        assertThat(validator.validate("2 schools in 2027年2月", quote).status()).isNotEqualTo("ENTAILED");
        assertThat(validator.validate("2027年2月覆盖600名教师", "600 teachers in 2027.").status()).isNotEqualTo("ENTAILED");
    }

    @Test
    void supportsNamedMonthDateOrdersAndPreservesPrecision() {
        for (String quote : new String[]{"December 15, 2026", "15 December 2026", "Dec. 15, 2026"}) {
            assertThat(validator.validate("2026年12月15日", quote).status()).as(quote).isEqualTo("ENTAILED");
            assertThat(validator.validate("Released before 2026-12", "Released before " + quote).status()).as(quote).isNotEqualTo("ENTAILED");
            assertThat(validator.validate("Not released in 2026-12", "Not released on " + quote).status()).as(quote).isNotEqualTo("ENTAILED");
            assertThat(validator.validate("2026年12月16日", quote).status()).as(quote).isEqualTo("CONTRADICTED");
        }
    }

    @Test
    void preservesEveryDigitOfUngroupedQuantities() {
        assertThat(validator.validate("预算300000", "预算300001").status()).isEqualTo("CONTRADICTED");
        assertThat(validator.validate("1024 people", "1025 people").status()).isEqualTo("CONTRADICTED");
        assertThat(validator.validate("300000 TWD", "300,000 TWD").status()).isEqualTo("ENTAILED");
    }

    @Test
    void namedDatesDoNotLeakDayOrYearAsStandaloneCounts() {
        assertThat(validator.facts("600 teachers on 15 December 2026").numbers())
                .extracting(ResearchTypedClaimValidator.NumberFact::value).containsExactly("600");
        assertThat(validator.facts("600 teachers on 15 December 2026").dates()).containsExactly("2026-12-15");
    }

    @Test
    void yearLikeDecimalWithUnitRemainsAQuantity() {
        assertThat(validator.validate("Memory 2027.2 GB", "Memory 2027.2 MB").reasonCodes()).contains("UNIT_CONTRADICTION");
        assertThat(validator.validate("Memory 2027.2 GB", "Memory 2027.3 GB").status()).isEqualTo("CONTRADICTED");
        assertThat(validator.facts("Memory 2027.2 GB").dates()).isEmpty();
    }
}
