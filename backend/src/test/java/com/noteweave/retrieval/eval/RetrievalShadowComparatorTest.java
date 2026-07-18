package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RetrievalShadowComparatorTest {
    @Test
    void shouldCompareOnlineShadowRankingWithTheSameGoldMetrics() throws Exception {
        Path goldSet = Path.of(getClass().getResource(
                "/retrieval/stage5-retrieval-gold-v1.json").toURI());
        Path shadow = Path.of(getClass().getResource(
                "/retrieval/stage5-shadow-fixture-v1.json").toURI());

        var report = new RetrievalShadowComparator().compare(goldSet, shadow);

        assertThat(report.shadowSnapshotVersion()).isEqualTo("es-shadow-fixture-20260714");
        assertThat(report.strategyProfile()).isEmpty();
        assertThat(report.baseline().overall().macroRecallAtK()).isEqualTo(1.0d);
        assertThat(report.shadow().overall().macroRecallAtK()).isEqualTo(0.5d);
        assertThat(report.shadow().overall().macroMrr()).isEqualTo(0.5d);
        assertThat(report.shadow().overall().macroCitationPrecision()).isEqualTo(0.5d);
        assertThat(report.shadow().overall().macroCitationCoverage()).isEqualTo(0.5d);
        assertThat(report.shadow().overall().refusalAccuracy()).isZero();
        assertThat(report.summary().meanTopKOverlap()).isCloseTo(
                1.0d / 3.0d, org.assertj.core.data.Offset.offset(0.000001d));
        assertThat(report.summary().meanPositionAgreement()).isEqualTo(0.25d);
        assertThat(report.summary().top1ChangedCount()).isEqualTo(3);
        assertThat(report.summary().macroRecallDelta()).isEqualTo(-0.5d);
        assertThat(report.summary().macroMrrDelta()).isEqualTo(-0.5d);
        assertThat(report.summary().macroCitationPrecisionDelta()).isEqualTo(-0.5d);
        assertThat(report.summary().macroCitationCoverageDelta()).isEqualTo(-0.5d);
        assertThat(report.summary().refusalAccuracyDelta()).isEqualTo(-1.0d);
        assertThat(report.summary().scopeViolationCount()).isEqualTo(1);
        assertThat(report.summary().p50LatencyMicros()).isEqualTo(800);
        assertThat(report.summary().p95LatencyMicros()).isEqualTo(1200);
        assertThat(report.summary().totalCandidateCount()).isEqualTo(11);
        assertThat(report.summary().averageCandidateCount()).isEqualTo(2.75d);
    }

    @Test
    void shouldPropagateStrategyProfileFromShadowSnapshot() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RetrievalGoldSet goldSet = objectMapper.readValue(
                Path.of(getClass().getResource(
                        "/retrieval/stage5-retrieval-gold-v1.json").toURI()).toFile(),
                RetrievalGoldSet.class);
        RetrievalShadowSnapshot fixture = objectMapper.readValue(
                Path.of(getClass().getResource(
                        "/retrieval/stage5-shadow-fixture-v1.json").toURI()).toFile(),
                RetrievalShadowSnapshot.class);
        RetrievalShadowSnapshot profiled = new RetrievalShadowSnapshot(
                fixture.schemaVersion(),
                fixture.snapshotVersion(),
                "  qa-retrieval-v2  ",
                fixture.cases());

        var report = new RetrievalShadowComparator().compare(goldSet, profiled);

        assertThat(report.strategyProfile()).isEqualTo("qa-retrieval-v2");
    }
}
