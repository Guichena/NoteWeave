package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RetrievalBenchmarkProfilerTest {
    @Test
    void shouldReportLatencyPercentilesAndDeterministicWorkloadCost() throws Exception {
        Path goldSet = Path.of(getClass().getResource(
                "/retrieval/stage5-retrieval-gold-v1.json").toURI());

        var report = new RetrievalBenchmarkProfiler().profile(goldSet, 1, 5);

        assertThat(report.datasetVersion()).isEqualTo("stage5-fixture-20260714");
        assertThat(report.baselineVersion()).isEqualTo("deterministic-bm25-v1");
        assertThat(report.warmupIterations()).isEqualTo(1);
        assertThat(report.measuredIterations()).isEqualTo(5);
        assertThat(report.minLatencyNanos()).isPositive();
        assertThat(report.p50LatencyNanos()).isBetween(
                report.minLatencyNanos(), report.p95LatencyNanos());
        assertThat(report.p95LatencyNanos()).isLessThanOrEqualTo(report.maxLatencyNanos());
        assertThat(report.averageLatencyNanos()).isPositive();
        assertThat(report.workload().caseCount()).isEqualTo(4);
        assertThat(report.workload().totalCandidateCount()).isEqualTo(11);
        assertThat(report.workload().allowedCandidateCount()).isEqualTo(9);
        assertThat(report.workload().queryTokenCount()).isEqualTo(29);
        assertThat(report.workload().candidateTokenCount()).isPositive();
        assertThat(report.workload().requestedTopK()).isEqualTo(7);
        assertThat(report.workload().returnedEvidenceCount()).isEqualTo(4);
        assertThat(report.workload().relevantEvidenceCount()).isEqualTo(4);
        assertThat(report.workload().expectedCitationCount()).isEqualTo(4);
    }
}
