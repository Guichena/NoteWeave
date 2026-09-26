package com.noteweave.retrieval.projection;

import com.noteweave.retrieval.projection.RetrievalBackfillService.RetrievalStatus;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RerankClient;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RetrievalReleaseGateService {
    private final JdbcTemplate jdbcTemplate;
    private final RetrievalBackfillService backfillService;
    private final EmbeddingClient embeddingClient;
    private final RerankClient rerankClient;
    private final RetrievalQualityReceiptService qualityReceiptService;
    private final boolean elasticsearchEnabled;

    @Autowired
    public RetrievalReleaseGateService(
            JdbcTemplate jdbcTemplate,
            RetrievalBackfillService backfillService,
            EmbeddingClient embeddingClient,
            RerankClient rerankClient,
            RetrievalQualityReceiptService qualityReceiptService,
            @Value("${noteweave.elasticsearch.enabled:true}") boolean elasticsearchEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.backfillService = backfillService;
        this.embeddingClient = embeddingClient;
        this.rerankClient = rerankClient;
        this.qualityReceiptService = qualityReceiptService;
        this.elasticsearchEnabled = elasticsearchEnabled;
    }

    public RetrievalReleaseGateService(
            JdbcTemplate jdbcTemplate,
            RetrievalBackfillService backfillService,
            EmbeddingClient embeddingClient,
            RerankClient rerankClient,
            RetrievalQualityReceiptService qualityReceiptService
    ) {
        this(jdbcTemplate, backfillService, embeddingClient, rerankClient,
                qualityReceiptService, true);
    }

    public GateResult evaluate(String workspaceId) {
        RetrievalStatus status = backfillService.status(workspaceId);
        List<String> violations = new ArrayList<>();
        if (!elasticsearchEnabled) violations.add("ELASTICSEARCH_PROVIDER_NOT_READY");
        if (!embeddingClient.isEnabled()) violations.add("EMBEDDING_PROVIDER_NOT_READY");
        if (!rerankClient.isEnabled()) violations.add("RERANK_PROVIDER_NOT_READY");
        var coverage = status.coverage();
        if (coverage.sourceCount() != coverage.readySourceCount()) violations.add("SOURCE_READY_COVERAGE_INCOMPLETE");
        if (coverage.sourceCount() != coverage.qaReadySourceCount()) violations.add("QA_PROJECTION_COVERAGE_INCOMPLETE");
        if (coverage.sourceCount() != coverage.noteReadySourceCount()) violations.add("NOTE_PROJECTION_COVERAGE_INCOMPLETE");
        boolean qaBuild = status.builds().stream().anyMatch(build ->
                build.projectionType() == RetrievalProjectionRepository.ProjectionType.QA_CHUNK
                        && build.status() == RetrievalIndexBuildRepository.BuildStatus.COMPLETED
                        && build.readyCount() == build.expectedCount() && build.failedCount() == 0);
        boolean noteBuild = status.builds().stream().anyMatch(build ->
                build.projectionType() == RetrievalProjectionRepository.ProjectionType.NOTE_SOURCE
                        && build.status() == RetrievalIndexBuildRepository.BuildStatus.COMPLETED
                        && build.readyCount() == build.expectedCount() && build.failedCount() == 0);
        if (!qaBuild) violations.add("QA_COMPLETED_BUILD_MISSING");
        if (!noteBuild) violations.add("NOTE_COMPLETED_BUILD_MISSING");
        long degradedRuns = countDegradedFinalPlanRuns(workspaceId);
        if (degradedRuns > 0) violations.add("FINAL_PLAN_DEGRADED_RUNS_PRESENT");
        List<RetrievalQualityReceiptService.Receipt> receipts =
                qualityReceiptService.latestFinalPlanReceipts(workspaceId);
        for (String plan : RetrievalQualityReceiptService.FINAL_PLANS) {
            boolean eligible = receipts.stream().anyMatch(receipt ->
                    plan.equals(receipt.planVersion()) && receipt.releaseEligible());
            if (!eligible) violations.add("QUALITY_GATE_RECEIPT_MISSING_OR_FAILED:" + plan);
        }
        return new GateResult(workspaceId, violations.isEmpty(), List.copyOf(violations),
                degradedRuns, status, receipts);
    }

    private long countDegradedFinalPlanRuns(String workspaceId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*) from answer_run
                where workspace_id = ? and status = 'COMPLETED'
                  and retrieval_plan_version in ('qa-weknora-hybrid-v1', 'note-marginalia-funnel-v1')
                  and (evidence_bundle_json like '%"degraded":true%'
                       or evidence_bundle_json like '%"degraded": true%')
                """, Long.class, workspaceId);
        return count == null ? 0 : count;
    }

    public record GateResult(String workspaceId, boolean releasable, List<String> violations,
                             long degradedFinalPlanRunCount, RetrievalStatus retrievalStatus,
                             List<RetrievalQualityReceiptService.Receipt> qualityReceipts) { }
}
