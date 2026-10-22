package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResearchSourceDateGroundingTest {
    @ParameterizedTest
    @ValueSource(strings = {"2025-03-13", "2025/3/13", "13-Mar-2025", "13 March 2025",
            "March 13, 2025", "Mar. 13th, 2025", "2025年3月13日", "Created:\n- 13-Mar-2025"})
    void recognizesCompleteLiteralDates(String quote) {
        assertThat(ResearchSourceDateGrounding.matches(quote, LocalDate.of(2025, 3, 13))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-03-14", "13-Mar-2024", "March 2025", "03/13/2025",
            "13/03/2025", "2025-03-130", "12025-03-13", "Marching 13, 2025", "2025-02-30"})
    void rejectsOtherDatesAmbiguityAndInvalidDates(String quote) {
        assertThat(ResearchSourceDateGrounding.matches(quote, LocalDate.of(2025, 3, 13))).isFalse();
    }

    @Test
    void validatesCalendarDates() {
        assertThat(ResearchSourceDateGrounding.matches("29-Feb-2024", LocalDate.of(2024, 2, 29))).isTrue();
        assertThat(ResearchSourceDateGrounding.matches("29-Feb-2025", LocalDate.of(2025, 2, 28))).isFalse();
    }

    @Test
    void acceptsNormalizedDateOnlyWhenExactQuoteIsArchived() {
        assertThat(reasons("2025-03-13", "13-Mar-2025", "Created:\n- 13-Mar-2025")).isEmpty();
        assertThat(reasons("2025-03-13", "13-Mar-2025", "Created:\n- 14-Mar-2025"))
                .contains("SOURCE_DATE_UNGROUNDED");
    }

    @Test
    void rejectsInventedNormalizedDateAndFutureDate() {
        assertThat(reasons("2025-03-14", "13-Mar-2025", "13-Mar-2025"))
                .contains("SOURCE_DATE_UNGROUNDED");
        assertThat(reasons("2027-03-13", "13-Mar-2027", "13-Mar-2027"))
                .contains("SOURCE_AFTER_CUTOFF").doesNotContain("SOURCE_DATE_UNGROUNDED");
    }

    private List<String> reasons(String date, String quote, String archive) {
        Map<String, Object> check = Map.of("cluster_key", "cluster:date", "cell_key", "row:date",
                "product", "MATCH", "branch", "APPLICABLE", "time", "APPLICABLE",
                "date", date, "date_quote", quote, "date_kind", "PUBLISHED");
        var envelope = new ResearchAgentCompletionEnvelope("research-agent-completion.v2", "task", "worker", 1, 1,
                "execution", "snapshot", "CANDIDATES_PROPOSED", Map.of("llm_calls", 1L), Map.of(), "trace",
                List.of(), List.of(), null, Map.of("source_scope_checks", Map.of("schema_version",
                "research-source-scope-checks.v1", "model_calls", 1, "checks", List.of(check))), "digest");
        var mapper = new ObjectMapper();
        var cluster = mapper.valueToTree(Map.of("cluster_key", "cluster:date", "cell_key", "row:date", "evidence_keys", List.of("ev:date")));
        var scope = mapper.valueToTree(Map.of("temporal_mode", "CURRENT", "as_of_date", "2026-10-21"));
        var source = new ResearchAgentCompletionCommitter.TrustedEvidenceSource("source", "title", "https://example.test/",
                "provider", "adapter", "EXTERNAL_ARCHIVED", "snapshot", "WEB", "example.test", "lineage", archive);
        var reasons = new ArrayList<String>();
        ResearchSourceScopeChecks.check(envelope, cluster, scope, Map.of("ev:date", source), reasons);
        return reasons;
    }
}
