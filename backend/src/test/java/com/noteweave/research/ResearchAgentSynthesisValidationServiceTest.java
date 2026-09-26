package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchAgentSynthesisValidationServiceTest {

    private final ResearchAgentSynthesisValidationService validator =
            new ResearchAgentSynthesisValidationService();

    @Test
    void acceptsExactAuditedClaimAndCitationRendering() {
        validator.validate(
                Set.of("cell-a"),
                List.of(Map.of(
                        "cell_key", "cell-a", "candidate_value", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                List.of(Map.of(
                        "cell_key", "cell-a", "claim_text", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                "## cell-a\nLatency is 50 ms\n[evidence-a]", Map.of(), "", false, false);
    }

    @Test
    void rejectsNewNumbersNotPresentInAuditedCell() {
        assertThatThrownBy(() -> validator.validate(
                Set.of("cell-a"),
                List.of(Map.of(
                        "cell_key", "cell-a", "candidate_value", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                List.of(Map.of(
                        "cell_key", "cell-a", "claim_text", "Latency is 20 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                "Latency is 20 ms [evidence-a]", Map.of(), "", false, false))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_SYNTHESIS_INVALID");
    }

    @Test
    void guardedCellMustRetainExplicitLimitation() {
        assertThatThrownBy(() -> validator.validate(
                Set.of("cell-a"),
                List.of(Map.of(
                        "cell_key", "cell-a", "candidate_value", "Result is uncertain",
                        "evidence_keys", List.of("evidence-a"), "guarded", true)),
                List.of(Map.of(
                        "cell_key", "cell-a", "claim_text", "Result is uncertain",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                "Result is uncertain [evidence-a]", Map.of(), "", false, false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void acceptsEvidenceBoundNarrativeWithoutChangingCanonicalClaim() {
        Map<String, Object> paragraph = Map.of(
                "text", "The measured latency is 50 ms.",
                "cell_keys", List.of("cell-a"),
                "evidence_keys", List.of("evidence-a"));
        validator.validate(
                Set.of("cell-a"),
                List.of(Map.of(
                        "cell_key", "cell-a", "candidate_value", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                List.of(Map.of(
                        "cell_key", "cell-a", "claim_text", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                "# Report\n## Latency\nThe measured latency is 50 ms. [evidence-a]",
                Map.of(
                        "schema_version", "research-reader-report.v1",
                        "title", "Report",
                        "executive_summary", "Verified findings.",
                        "sections", List.of(Map.of("heading", "Latency", "paragraphs", List.of(paragraph)))),
                "", false, true);
    }

    @Test
    void rejectsNarrativeTypedFactOutsideBoundCell() {
        Map<String, Object> paragraph = Map.of(
                "text", "The measured latency is 20 ms.",
                "cell_keys", List.of("cell-a"),
                "evidence_keys", List.of("evidence-a"));
        assertThatThrownBy(() -> validator.validate(
                Set.of("cell-a"),
                List.of(Map.of(
                        "cell_key", "cell-a", "candidate_value", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                List.of(Map.of(
                        "cell_key", "cell-a", "claim_text", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                "The measured latency is 20 ms. [evidence-a]",
                Map.of(
                        "schema_version", "research-reader-report.v1",
                        "title", "Report",
                        "executive_summary", "Verified findings.",
                        "sections", List.of(Map.of("heading", "Latency", "paragraphs", List.of(paragraph)))),
                "", false, true)).isInstanceOf(BusinessException.class);
    }

    @Test
    void acceptsEvidenceBoundComparisonTable() {
        Map<String, Object> paragraph = Map.of(
                "text", "The measured latency is 50 ms.",
                "cell_keys", List.of("cell-a"),
                "evidence_keys", List.of("evidence-a"));
        Map<String, Object> table = Map.of(
                "columns", List.of("Dimension", "Result"),
                "rows", List.of(Map.of(
                        "cells", List.of("Latency", "50 ms"),
                        "cell_keys", List.of("cell-a"),
                        "evidence_keys", List.of("evidence-a"))));
        validator.validate(
                Set.of("cell-a"),
                List.of(Map.of(
                        "cell_key", "cell-a", "candidate_value", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                List.of(Map.of(
                        "cell_key", "cell-a", "claim_text", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                "# Report\n## Latency\nThe measured latency is 50 ms. [evidence-a]\n"
                        + "## 对比表\n| Dimension | Result |\n| --- | --- |\n| Latency | 50 ms [evidence-a] |",
                Map.of(
                        "schema_version", "research-reader-report.v1",
                        "title", "Report",
                        "executive_summary", "Verified findings.",
                        "sections", List.of(Map.of("heading", "Latency", "paragraphs", List.of(paragraph))),
                        "comparison_table", table),
                "", false, true);
    }

    @Test
    void rejectsComparisonTableTypedFactOutsideBoundCell() {
        Map<String, Object> paragraph = Map.of(
                "text", "The measured latency is 50 ms.",
                "cell_keys", List.of("cell-a"),
                "evidence_keys", List.of("evidence-a"));
        Map<String, Object> table = Map.of(
                "columns", List.of("Dimension", "Result"),
                "rows", List.of(Map.of(
                        "cells", List.of("Latency", "20 ms"),
                        "cell_keys", List.of("cell-a"),
                        "evidence_keys", List.of("evidence-a"))));
        assertThatThrownBy(() -> validator.validate(
                Set.of("cell-a"),
                List.of(Map.of(
                        "cell_key", "cell-a", "candidate_value", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                List.of(Map.of(
                        "cell_key", "cell-a", "claim_text", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                "The measured latency is 50 ms. [evidence-a]\n| Latency | 20 ms [evidence-a] |",
                Map.of(
                        "schema_version", "research-reader-report.v1",
                        "title", "Report",
                        "executive_summary", "Verified findings.",
                        "sections", List.of(Map.of("heading", "Latency", "paragraphs", List.of(paragraph))),
                        "comparison_table", table),
                "", false, true)).isInstanceOf(BusinessException.class);
    }

    @Test
    void acceptsComparisonVersionsFromTrustedQuestionContext() {
        Map<String, Object> paragraph = Map.of(
                "text", "PostgreSQL 17 and MySQL 8.4 provide JSON support.",
                "cell_keys", List.of("cell-a"),
                "evidence_keys", List.of("evidence-a"));
        Map<String, Object> table = Map.of(
                "columns", List.of("Product", "Result"),
                "rows", List.of(
                        Map.of("cells", List.of("PostgreSQL 17", "JSON support"), "cell_keys", List.of("cell-a"), "evidence_keys", List.of("evidence-a")),
                        Map.of("cells", List.of("MySQL 8.4", "JSON support"), "cell_keys", List.of("cell-a"), "evidence_keys", List.of("evidence-a"))));
        validator.validate(
                Set.of("cell-a"),
                List.of(Map.of("cell_key", "cell-a", "candidate_value", "JSON support", "evidence_keys", List.of("evidence-a"), "guarded", false)),
                List.of(Map.of("cell_key", "cell-a", "claim_text", "JSON support", "evidence_keys", List.of("evidence-a"), "guarded", false)),
                "PostgreSQL 17 and MySQL 8.4 provide JSON support. [evidence-a]\n"
                        + "| PostgreSQL 17 | JSON support [evidence-a] |\n"
                        + "| MySQL 8.4 | JSON support [evidence-a] |",
                Map.of(
                        "schema_version", "research-reader-report.v1",
                        "title", "Comparison",
                        "executive_summary", "Summary",
                        "sections", List.of(Map.of("heading", "Result", "paragraphs", List.of(paragraph))),
                        "comparison_table", table),
                "Compare PostgreSQL 17 and MySQL 8.4",
                true,
                true);
    }

    @Test
    void rejectsComparisonNarrativeWithoutRequiredTable() {
        Map<String, Object> paragraph = Map.of(
                "text", "JSON support",
                "cell_keys", List.of("cell-a"),
                "evidence_keys", List.of("evidence-a"));
        assertThatThrownBy(() -> validator.validate(
                Set.of("cell-a"),
                List.of(Map.of("cell_key", "cell-a", "candidate_value", "JSON support", "evidence_keys", List.of("evidence-a"), "guarded", false)),
                List.of(Map.of("cell_key", "cell-a", "claim_text", "JSON support", "evidence_keys", List.of("evidence-a"), "guarded", false)),
                "JSON support [evidence-a]",
                Map.of(
                        "schema_version", "research-reader-report.v1",
                        "title", "Comparison",
                        "executive_summary", "Summary",
                        "sections", List.of(Map.of("heading", "Result", "paragraphs", List.of(paragraph)))),
                "Compare PostgreSQL 17 and MySQL 8.4",
                true,
                true)).isInstanceOf(BusinessException.class);
    }
}
