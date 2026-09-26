package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.retrieval.projection.RetrievalBackfillService.Coverage;
import com.noteweave.retrieval.projection.RetrievalBackfillService.RetrievalStatus;
import com.noteweave.retrieval.projection.RetrievalIndexBuildRepository.BuildStatus;
import com.noteweave.retrieval.projection.RetrievalIndexBuildRepository.IndexBuild;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RerankClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RetrievalReleaseGateServiceTest {
    @Test
    void releaseRequiresProvidersDualCoverageCompletedBuildsAndNoDegradedRuns() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RetrievalBackfillService backfill = mock(RetrievalBackfillService.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        RerankClient rerank = mock(RerankClient.class);
        RetrievalQualityReceiptService receipts = mock(RetrievalQualityReceiptService.class);
        when(embedding.isEnabled()).thenReturn(true);
        when(rerank.isEnabled()).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("workspace"))).thenReturn(0L);
        when(backfill.status("workspace")).thenReturn(new RetrievalStatus(
                "workspace", new Coverage(3, 3, 3, 3),
                List.of(build(ProjectionType.QA_CHUNK), build(ProjectionType.NOTE_SOURCE)), List.of()));
        when(receipts.latestFinalPlanReceipts("workspace")).thenReturn(List.of(
                receipt("qa-weknora-hybrid-v1"), receipt("note-marginalia-funnel-v1")));

        var result = new RetrievalReleaseGateService(jdbc, backfill, embedding, rerank, receipts)
                .evaluate("workspace");

        assertThat(result.releasable()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void anyIncompleteFinalStateBlocksRelease() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RetrievalBackfillService backfill = mock(RetrievalBackfillService.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        RerankClient rerank = mock(RerankClient.class);
        RetrievalQualityReceiptService receipts = mock(RetrievalQualityReceiptService.class);
        when(embedding.isEnabled()).thenReturn(false);
        when(rerank.isEnabled()).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("workspace"))).thenReturn(2L);
        when(backfill.status("workspace")).thenReturn(new RetrievalStatus(
                "workspace", new Coverage(3, 2, 2, 1), List.of(), List.of()));

        when(receipts.latestFinalPlanReceipts("workspace")).thenReturn(List.of());
        var result = new RetrievalReleaseGateService(jdbc, backfill, embedding, rerank, receipts)
                .evaluate("workspace");

        assertThat(result.releasable()).isFalse();
        assertThat(result.violations()).contains(
                "EMBEDDING_PROVIDER_NOT_READY",
                "SOURCE_READY_COVERAGE_INCOMPLETE",
                "QA_PROJECTION_COVERAGE_INCOMPLETE",
                "NOTE_PROJECTION_COVERAGE_INCOMPLETE",
                "QA_COMPLETED_BUILD_MISSING",
                "NOTE_COMPLETED_BUILD_MISSING",
                "FINAL_PLAN_DEGRADED_RUNS_PRESENT",
                "QUALITY_GATE_RECEIPT_MISSING_OR_FAILED:qa-weknora-hybrid-v1",
                "QUALITY_GATE_RECEIPT_MISSING_OR_FAILED:note-marginalia-funnel-v1");
    }

    @Test
    void disabledElasticsearchBlocksReleaseEvenWhenHistoricalBuildsAreComplete() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RetrievalBackfillService backfill = mock(RetrievalBackfillService.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        RerankClient rerank = mock(RerankClient.class);
        RetrievalQualityReceiptService receipts = mock(RetrievalQualityReceiptService.class);
        when(embedding.isEnabled()).thenReturn(true);
        when(rerank.isEnabled()).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("workspace"))).thenReturn(0L);
        when(backfill.status("workspace")).thenReturn(new RetrievalStatus(
                "workspace", new Coverage(3, 3, 3, 3),
                List.of(build(ProjectionType.QA_CHUNK), build(ProjectionType.NOTE_SOURCE)), List.of()));
        when(receipts.latestFinalPlanReceipts("workspace")).thenReturn(List.of(
                receipt("qa-weknora-hybrid-v1"), receipt("note-marginalia-funnel-v1")));

        var result = new RetrievalReleaseGateService(jdbc, backfill, embedding, rerank, receipts, false)
                .evaluate("workspace");

        assertThat(result.releasable()).isFalse();
        assertThat(result.violations()).containsExactly("ELASTICSEARCH_PROVIDER_NOT_READY");
    }

    private IndexBuild build(ProjectionType type) {
        Instant now = Instant.now();
        return new IndexBuild(type.name(), "workspace", type, null, "index-" + type.name(),
                "openai-compatible", "embedding", 1024, "embedding:1024", "schema-v1",
                BuildStatus.COMPLETED, 3, 3, 0, null, now, now, now, now, now);
    }

    private RetrievalQualityReceiptService.Receipt receipt(String plan) {
        return new RetrievalQualityReceiptService.Receipt(plan, "workspace", plan, plan + "-gold-v1",
                "report-v1", "sha256:" + "a".repeat(64), true, 10, 0, 0, 0, 0,
                true, "tester", Instant.now());
    }
}
