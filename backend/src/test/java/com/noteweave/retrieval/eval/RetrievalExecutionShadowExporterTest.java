package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.EvidenceBundleSnapshot;
import com.noteweave.answer.strategy.RetrievalExecutionTrace;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetrievalExecutionShadowExporterTest {

    private static final String SALT = "stage5-online-shadow-salt";

    @Test
    void shouldMapPersistedOnlineTraceAndBundleToShadowSchema() {
        EvidenceBundle bundle = new EvidenceBundle(
                "bundle", "qa-passage-v1", List.of(new EvidenceBundle.Evidence(
                "passage:chunk-1", "PASSAGE", "source-1", "snapshot-1", "chunk-1",
                "", "", "private title", "private content", "chunk:1",
                7, 7, 7, "workspace-source:source-1", Instant.now(),
                "fulltext:bm25", 15, Map.of("raw_title", "private title")
        )), false, List.of(), Instant.now());
        RetrievalExecutionTrace trace = new RetrievalExecutionTrace(
                RetrievalExecutionTrace.SCHEMA_VERSION,
                "qa-passage-v1",
                1_234,
                12,
                6,
                1,
                15,
                List.of(),
                List.of()
        );
        RetrievalSnapshotSanitizer sanitizer = new RetrievalSnapshotSanitizer();
        RetrievalExecutionShadowExporter exporter =
                new RetrievalExecutionShadowExporter(sanitizer);

        var snapshot = exporter.export(
                "qa-passage-v1-run-1",
                List.of(new RetrievalExecutionShadowExporter.CaseExecution(
                        "case-1",
                        trace,
                        EvidenceBundleSnapshot.from(bundle),
                        Map.of("chunk-1", List.of("citation-1"))
                )),
                SALT
        );

        assertThat(snapshot.schemaVersion())
                .isEqualTo(RetrievalShadowComparator.SHADOW_SCHEMA_VERSION);
        assertThat(snapshot.cases()).singleElement().satisfies(item -> {
            assertThat(item.caseId()).isEqualTo(sanitizer.pseudonym("case", "case-1", SALT));
            assertThat(item.latencyMicros()).isEqualTo(1_234);
            assertThat(item.candidateCount()).isEqualTo(12);
            assertThat(item.rankedEvidence()).singleElement().satisfies(ranked -> {
                assertThat(ranked.evidenceId())
                        .isEqualTo(sanitizer.pseudonym("evidence", "chunk-1", SALT));
                assertThat(ranked.sourceId())
                        .isEqualTo(sanitizer.pseudonym("source", "source-1", SALT));
                assertThat(ranked.score()).isEqualTo(7);
                assertThat(ranked.citationIds()).containsExactly(
                        sanitizer.pseudonym("citation", "citation-1", SALT));
            });
        });
        assertThat(snapshot.toString())
                .doesNotContain("private title")
                .doesNotContain("private content");
    }

    @Test
    void shouldUsePrimaryHitCountForElasticsearchPath() {
        RetrievalExecutionTrace trace = traceWithSteps(
                6,
                step(6, Map.of(
                        "primary_hit_count", 21L,
                        "mysql_fallback_used", 0L,
                        "mysql_candidate_count", 80L
                ))
        );

        assertThat(exportCandidateCount(trace)).isEqualTo(21);
    }

    @Test
    void shouldUseOnlyMysqlCandidateCountWhenFallbackWasUsed() {
        RetrievalExecutionTrace trace = traceWithSteps(
                6,
                step(6, Map.of(
                        "primary_hit_count", 12L,
                        "mysql_fallback_used", 1L,
                        "mysql_candidate_count", 80L
                ))
        );

        assertThat(exportCandidateCount(trace)).isEqualTo(80);
    }

    @Test
    void shouldUseStepRawCandidateCountWhenPathMeasurementsAreMissing() {
        RetrievalExecutionTrace trace = traceWithSteps(
                99,
                step(7, Map.of("selected_count", 4L)),
                step(5, Map.of())
        );

        assertThat(exportCandidateCount(trace)).isEqualTo(12);
    }

    @Test
    void shouldSaturateCandidateCountOnOverflow() {
        RetrievalExecutionTrace trace = traceWithSteps(
                1,
                step(1, Map.of("primary_hit_count", Long.MAX_VALUE)),
                step(1, Map.of())
        );

        assertThat(exportCandidateCount(trace)).isEqualTo(Integer.MAX_VALUE);
    }

    private int exportCandidateCount(RetrievalExecutionTrace trace) {
        EvidenceBundle bundle = new EvidenceBundle(
                "bundle", "qa-passage-v1", List.of(), false, List.of(), Instant.now());
        RetrievalExecutionShadowExporter exporter = new RetrievalExecutionShadowExporter(
                new RetrievalSnapshotSanitizer());
        var snapshot = exporter.export(
                "qa-passage-v1-run",
                List.of(new RetrievalExecutionShadowExporter.CaseExecution(
                        "case", trace, EvidenceBundleSnapshot.from(bundle), Map.of())),
                SALT
        );
        return snapshot.cases().get(0).candidateCount();
    }

    private RetrievalExecutionTrace traceWithSteps(
            int rawCandidateCount,
            RetrievalExecutionTrace.StepTrace... steps
    ) {
        return new RetrievalExecutionTrace(
                RetrievalExecutionTrace.SCHEMA_VERSION,
                "qa-passage-v1",
                1_234,
                rawCandidateCount,
                6,
                1,
                15,
                List.of(steps),
                List.of()
        );
    }

    private RetrievalExecutionTrace.StepTrace step(
            int rawCandidateCount,
            Map<String, Long> measurements
    ) {
        return new RetrievalExecutionTrace.StepTrace(
                0,
                "QA_PASSAGE",
                6,
                rawCandidateCount,
                Math.min(rawCandidateCount, 6),
                100,
                false,
                List.of(),
                measurements
        );
    }
}
