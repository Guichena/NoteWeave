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
    void rejectsSummaryWithInventedNumbersOrUnknownEvidence() {
        for (String summary : List.of("PostgreSQL 999 [evidence:pg].", "An invented finding [evidence:unknown].")) {
            assertThatThrownBy(() -> validateMultiCellReport(summary, validParagraphs(), Map.of()))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void rejectsCrossEntityCitationEvenWhenBothCellsAreBound() {
        assertThatThrownBy(() -> validateMultiCellReport("Verified findings.", List.of(
                paragraph("PostgreSQL indexes virtual columns [evidence:mysql].", List.of("pg", "mysql"), List.of("evidence:mysql")),
                validParagraphs().get(1)), Map.of())).isInstanceOf(BusinessException.class);
    }

    @Test
    void acceptsComparisonSentenceThatNamesBothSubjectsAndCitesOneOfThem() {
        validateMultiCellReport("Verified findings.", List.of(
                paragraph("Unlike MySQL, PostgreSQL 17 uses GIN [evidence:pg].", List.of("pg"), List.of("evidence:pg")),
                validParagraphs().get(1)), Map.of());
    }

    @Test
    void percentWrittenAsAWordInTheQuoteSupportsThePercentSign() {
        String prose = "居家办公的主观生产力均值为60.6% [evidence:survey]。";
        java.util.function.Function<String, Map<String, Object>> report = text -> Map.of(
                "schema_version", "research-reader-report.v1", "title", "Report", "executive_summary", "Verified findings.",
                "sections", List.of(Map.of("heading", "Survey", "paragraphs", List.of(paragraph(
                        text, List.of("survey"), List.of("evidence:survey"))))));
        List<Map<String, Object>> cells = List.of(Map.of("cell_key", "survey", "candidate_value", "主观生产力均值为60.6%",
                "entity_label", "Japanese survey", "evidence_keys", List.of("evidence:survey"), "evidence_quotes", List.of(Map.of(
                        "evidence_key", "evidence:survey", "quote", "the mean subjective productivity of WFH was 60.6 percent"))));
        List<Map<String, Object>> claims = List.of(Map.of("cell_key", "survey", "claim_text", "主观生产力均值为60.6%",
                "evidence_keys", List.of("evidence:survey")));
        validator.validate(Set.of("survey"), cells, claims, prose, report.apply(prose), "", false, true);
        String invented = "居家办公的主观生产力均值为61% [evidence:survey]。";
        assertThatThrownBy(() -> validator.validate(Set.of("survey"), cells, claims, invented, report.apply(invented), "", false, true))
                .isInstanceOf(BusinessException.class).hasMessageContaining("61%");
    }

    @Test
    void rejectsBorrowingNumbersFromAnotherSentenceCitation() {
        assertThatThrownBy(() -> validateMultiCellReport("Verified findings.", List.of(
                paragraph("PostgreSQL 17 supports GIN [evidence:pg]. MySQL supports version 17 [evidence:mysql].",
                        List.of("pg", "mysql"), List.of("evidence:pg", "evidence:mysql"))), Map.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void automaticClaimsCannotReplaceMissingBodyCoverage() {
        assertThatThrownBy(() -> validateMultiCellReport("Verified findings.", List.of(validParagraphs().get(0)), Map.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void acceptsCompleteProseWithEachSentencesOwnCitation() {
        validateMultiCellReport("PostgreSQL 17 uses GIN [evidence:pg].", validParagraphs(), Map.of());
    }

    @Test
    void tableRowCannotAttributeAnotherProductsEvidenceToItsSubject() {
        Map<String, Object> table = Map.of("columns", List.of("Product", "Finding"), "rows", List.of(Map.of(
                "cells", List.of("PostgreSQL 17", "Virtual column indexes [evidence:mysql]"),
                "cell_keys", List.of("pg", "mysql"), "evidence_keys", List.of("evidence:mysql"))));
        assertThatThrownBy(() -> validateMultiCellReport("Verified findings.", validParagraphs(), table))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void tableProductHeadersAlsoConstrainCitationEntities() {
        Map<String, Object> table = Map.of("columns", List.of("Aspect", "PostgreSQL 17", "MySQL 8.4"), "rows", List.of(Map.of(
                "cells", List.of("Indexes", "Virtual columns [evidence:mysql]", "GIN [evidence:pg]"),
                "cell_keys", List.of("pg", "mysql"), "evidence_keys", List.of("evidence:mysql", "evidence:pg"))));
        assertThatThrownBy(() -> validateMultiCellReport("Verified findings.", validParagraphs(), table))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void missingComparisonCellDoesNotBorrowAnotherProductsEvidence() {
        Map<String, Object> table = Map.of("columns", List.of("Aspect", "PostgreSQL 17", "MySQL 8.4"), "rows", List.of(Map.of(
                "cells", List.of("Indexes", "GIN [evidence:pg]", "-"),
                "cell_keys", List.of("pg"), "evidence_keys", List.of("evidence:pg"))));
        validateMultiCellReport("Verified findings.", validParagraphs(), table);
    }

    @Test
    void declaredEvidenceMustMatchTheActualInlineCitations() {
        assertThatThrownBy(() -> validateMultiCellReport("Verified findings.", List.of(
                paragraph("PostgreSQL 17 uses GIN [evidence:pg].", List.of("pg", "mysql"), List.of("evidence:mysql")),
                validParagraphs().get(1)), Map.of())).isInstanceOf(BusinessException.class);
    }

    @Test
    void chineseAdjacentNumbersMustBeSupportedByTheirOwnQuotes() {
        assertThatThrownBy(() -> validateMultiCellReport("Verified findings.", List.of(
                paragraph("PostgreSQL有999种索引 [evidence:pg]。", List.of("pg"), List.of("evidence:pg")),
                validParagraphs().get(1)), Map.of())).isInstanceOf(BusinessException.class);
    }

    @Test
    void versionPrefixDoesNotIntroduceANewNumericFact() {
        String prose = "Atlas在v1.2版支持该功能 [evidence:version]。";
        validator.validate(Set.of("version"), List.of(Map.of(
                        "cell_key", "version", "candidate_value", "Version 1.2", "entity_label", "Atlas",
                        "evidence_keys", List.of("evidence:version"), "evidence_quotes", List.of(Map.of(
                                "evidence_key", "evidence:version", "quote", "Atlas Version 1.2 supports it.")))),
                List.of(Map.of("cell_key", "version", "claim_text", "Version 1.2", "evidence_keys", List.of("evidence:version"))),
                prose, Map.of("schema_version", "research-reader-report.v1", "title", "Report", "executive_summary", "Verified findings.",
                        "sections", List.of(Map.of("heading", "Version", "paragraphs", List.of(paragraph(
                                prose, List.of("version"), List.of("evidence:version")))))), "", false, true);
    }

    @Test
    void versionTouchingProductNameIsNotTruncatedToLastComponent() {
        String prose = "Atlas v1.28支持该功能 [evidence:version]。";
        validator.validate(Set.of("version"), List.of(Map.of(
                        "cell_key", "version", "candidate_value", "Atlas v1.28 supports it", "entity_label", "Atlas",
                        "evidence_keys", List.of("evidence:version"), "evidence_quotes", List.of(Map.of(
                                "evidence_key", "evidence:version", "quote", "Atlasv1.28以降で利用できます")))),
                List.of(Map.of("cell_key", "version", "claim_text", "Atlas v1.28 supports it", "evidence_keys", List.of("evidence:version"))),
                prose, Map.of("schema_version", "research-reader-report.v1", "title", "Report", "executive_summary", "Verified findings.",
                        "sections", List.of(Map.of("heading", "Version", "paragraphs", List.of(paragraph(
                                prose, List.of("version"), List.of("evidence:version")))))), "", false, true);
        assertThatThrownBy(() -> validator.validate(Set.of("version"), List.of(Map.of(
                        "cell_key", "version", "candidate_value", "Atlasv1.28", "evidence_keys", List.of("evidence:version"))),
                List.of(Map.of("cell_key", "version", "claim_text", "Atlasv1.28", "evidence_keys", List.of("evidence:version"))),
                "Atlas有28个选项 [evidence:version]。", Map.of("schema_version", "research-reader-report.v1", "title", "Report",
                        "executive_summary", "Verified findings.", "sections", List.of(Map.of("heading", "Version", "paragraphs", List.of(paragraph(
                                "Atlas有28个选项 [evidence:version]。", List.of("version"), List.of("evidence:version")))))), "", false, true))
                .isInstanceOf(BusinessException.class);
    }

    private static Map<String, Object> paragraph(String prose, List<String> cells, List<String> evidence) {
        return Map.of("text", prose, "cell_keys", cells, "evidence_keys", evidence);
    }

    private static List<Map<String, Object>> validParagraphs() {
        return List.of(paragraph("PostgreSQL 17 uses GIN [evidence:pg].", List.of("pg"), List.of("evidence:pg")),
                paragraph("MySQL 8.4 indexes virtual columns [evidence:mysql].", List.of("mysql"), List.of("evidence:mysql")));
    }

    private void validateMultiCellReport(String summary, List<Map<String, Object>> paragraphs, Map<String, Object> table) {
        List<Map<String, Object>> cells = List.of(
                Map.of("cell_key", "pg", "candidate_value", "GIN indexes", "entity_label", "PostgreSQL 17",
                        "evidence_keys", List.of("evidence:pg"), "evidence_quotes", List.of(Map.of(
                                "evidence_key", "evidence:pg", "quote", "PostgreSQL 17 supports GIN indexes."))),
                Map.of("cell_key", "mysql", "candidate_value", "Virtual column indexes", "entity_label", "MySQL 8.4",
                        "evidence_keys", List.of("evidence:mysql"), "evidence_quotes", List.of(Map.of(
                                "evidence_key", "evidence:mysql", "quote", "MySQL 8.4 indexes virtual columns."))));
        List<Map<String, Object>> claims = cells.stream().map(cell -> Map.<String, Object>of(
                "cell_key", cell.get("cell_key"), "claim_text", cell.get("candidate_value"),
                "evidence_keys", cell.get("evidence_keys"))).toList();
        String markdown = summary + "\n" + paragraphs.stream().map(item -> item.get("text").toString())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (table.get("rows") instanceof List<?> rows) {
            for (Object item : rows) {
                if (item instanceof Map<?, ?> row && row.get("cells") instanceof List<?> values) {
                    markdown += "\n| " + values.stream().map(Object::toString)
                            .collect(java.util.stream.Collectors.joining(" | ")) + " |";
                }
            }
        }
        validator.validate(Set.of("pg", "mysql"), cells, claims, markdown,
                Map.of("schema_version", "research-reader-report.v1", "title", "Findings", "executive_summary", summary,
                        "sections", List.of(Map.of("heading", "Indexes", "paragraphs", paragraphs)), "comparison_table", table),
                "", false, true);
    }

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

    private static Map<String, Object> leadNarrative(String prose) {
        return Map.of(
                "schema_version", "research-reader-report.v1",
                "title", "Report",
                "executive_summary", "Verified findings.",
                "sections", List.of(Map.of("heading", "Result", "paragraphs", List.of(
                        Map.of("text", "The measured latency is 50 ms.", "cell_keys", List.of("cell-a"),
                                "evidence_keys", List.of("evidence-a")),
                        Map.of("text", prose, "cell_keys", List.of("cell-b"),
                                "evidence_keys", List.of("evidence-b"))))));
    }

    private static final List<Map<String, Object>> LEADS = List.of(Map.of(
            "cell_key", "cell-b", "candidate_value", "Throughput is 900 rps", "evidence_level", "SOURCED",
            "evidence_keys", List.of("evidence-b"), "supporting_quotes", List.of("[Vendor blog] Throughput is 900 rps")));

    @Test
    void narrativeMayCiteSourcedLeadsWithTheirOwnEvidence() {
        String prose = "据第三方资料（未经核实），吞吐量为 900 rps。";
        validator.validate(
                Set.of("cell-a"),
                List.of(Map.of("cell_key", "cell-a", "candidate_value", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                List.of(Map.of("cell_key", "cell-a", "claim_text", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                "# Report\nThe measured latency is 50 ms. [evidence-a]\n" + prose + " [evidence-b]",
                leadNarrative(prose), "", false, true, LEADS);
    }

    @Test
    void leadParagraphStillNeedsItsNumbersInTheLeadQuote() {
        String prose = "据第三方资料（未经核实），吞吐量为 1200 rps。";
        assertThatThrownBy(() -> validator.validate(
                Set.of("cell-a"),
                List.of(Map.of("cell_key", "cell-a", "candidate_value", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                List.of(Map.of("cell_key", "cell-a", "claim_text", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                "# Report\nThe measured latency is 50 ms. [evidence-a]\n" + prose + " [evidence-b]",
                leadNarrative(prose), "", false, true, LEADS)).isInstanceOf(BusinessException.class);
    }

    @Test
    void withoutLeadsAParagraphOnAnUnverifiedCellIsRejected() {
        String prose = "据第三方资料（未经核实），吞吐量为 900 rps。";
        assertThatThrownBy(() -> validator.validate(
                Set.of("cell-a"),
                List.of(Map.of("cell_key", "cell-a", "candidate_value", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                List.of(Map.of("cell_key", "cell-a", "claim_text", "Latency is 50 ms",
                        "evidence_keys", List.of("evidence-a"), "guarded", false)),
                "# Report\nThe measured latency is 50 ms. [evidence-a]\n" + prose + " [evidence-b]",
                leadNarrative(prose), "", false, true)).isInstanceOf(BusinessException.class);
    }

    @Test
    void backgroundNotesMustStayUncitedInsideTheirLabelledSection() {
        String note = "网络往返和排队通常是延迟的主要来源。";
        String body = "# Report\nThe measured latency is 50 ms. [evidence-a]\n\n";
        String labelled = body + ResearchAgentSynthesisValidationService.BACKGROUND_HEADING_ZH + "\n\n- " + note + "\n";
        validator.validate(Set.of("cell-a"), cellA(), claimA(), labelled, noteNarrative(note), "", false, true);

        // 混进正文、没有标明未经核验时拒收
        assertThatThrownBy(() -> validator.validate(Set.of("cell-a"), cellA(), claimA(), body + note + "\n",
                noteNarrative(note), "", false, true)).isInstanceOf(BusinessException.class);
        // 借用证据编号冒充已核验的结论时拒收
        String cited = "网络往返是延迟的主要来源 [evidence:abc]。";
        assertThatThrownBy(() -> validator.validate(Set.of("cell-a"), cellA(), claimA(),
                body + ResearchAgentSynthesisValidationService.BACKGROUND_HEADING_ZH + "\n\n- " + cited + "\n",
                noteNarrative(cited), "", false, true)).isInstanceOf(BusinessException.class);
    }

    private static List<Map<String, Object>> cellA() {
        return List.of(Map.of("cell_key", "cell-a", "candidate_value", "Latency is 50 ms",
                "evidence_keys", List.of("evidence-a"), "guarded", false));
    }

    private static List<Map<String, Object>> claimA() {
        return List.of(Map.of("cell_key", "cell-a", "claim_text", "Latency is 50 ms",
                "evidence_keys", List.of("evidence-a"), "guarded", false));
    }

    private static Map<String, Object> noteNarrative(String note) {
        return Map.of(
                "schema_version", "research-reader-report.v1",
                "title", "Report",
                "executive_summary", "Verified findings.",
                "sections", List.of(Map.of("heading", "Result", "paragraphs", List.of(
                        Map.of("text", "The measured latency is 50 ms.", "cell_keys", List.of("cell-a"),
                                "evidence_keys", List.of("evidence-a"))))),
                "background_notes", List.of(Map.of("point", "延迟受哪些因素影响", "text", note)));
    }
}
