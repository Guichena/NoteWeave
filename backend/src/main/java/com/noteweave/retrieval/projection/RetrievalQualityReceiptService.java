package com.noteweave.retrieval.projection;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.security.AuditActorProvider;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RetrievalQualityReceiptService {
    public static final Set<String> FINAL_PLANS = Set.of(
            "qa-weknora-hybrid-v1", "note-marginalia-funnel-v1");
    private static final Map<String, Set<String>> REQUIRED_ABLATIONS = Map.of(
            "qa-weknora-hybrid-v1", Set.of(
                    "keyword-only", "vector-only", "hybrid-rrf", "hybrid-rrf-rerank",
                    "full-pipeline-evidence-budget"),
            "note-marginalia-funnel-v1", Set.of(
                    "metadata-only", "metadata-journal", "metadata-semantic",
                    "metadata-journal-semantic", "full-recall-relations",
                    "full-recall-relations-rerank-quota"));

    private final JdbcTemplate jdbcTemplate;
    private final AuditActorProvider actorProvider;

    public RetrievalQualityReceiptService(JdbcTemplate jdbcTemplate, AuditActorProvider actorProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.actorProvider = actorProvider;
    }

    public Receipt record(String workspaceId, RecordRequest request) {
        validate(request);
        String id = Ids.newId();
        String actor = actorProvider.currentOrSystem("RETRIEVAL_QUALITY_GATE");
        jdbcTemplate.update("""
                insert into retrieval_quality_receipt(
                    id, workspace_id, plan_version, dataset_version, report_version, report_sha256,
                    passed, case_count, scope_violation_count, current_snapshot_error_count,
                    citation_ownership_error_count, degraded_case_count, ablation_complete, created_by
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, workspaceId, request.planVersion(), request.datasetVersion(),
                request.reportVersion(), request.reportSha256(), request.passed(), request.caseCount(),
                request.scopeViolationCount(), request.currentSnapshotErrorCount(),
                request.citationOwnershipErrorCount(), request.degradedCaseCount(),
                true, actor);
        return findById(id);
    }

    public List<Receipt> latestFinalPlanReceipts(String workspaceId) {
        return jdbcTemplate.query("""
                select r.* from retrieval_quality_receipt r
                where r.workspace_id = ? and r.plan_version in (?, ?)
                  and r.created_at = (select max(x.created_at) from retrieval_quality_receipt x
                                      where x.workspace_id = r.workspace_id and x.plan_version = r.plan_version)
                order by r.plan_version
                """, this::map, workspaceId, "qa-weknora-hybrid-v1", "note-marginalia-funnel-v1");
    }

    private Receipt findById(String id) {
        return jdbcTemplate.queryForObject("select * from retrieval_quality_receipt where id = ?", this::map, id);
    }

    private Receipt map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Receipt(rs.getString("id"), rs.getString("workspace_id"),
                rs.getString("plan_version"), rs.getString("dataset_version"),
                rs.getString("report_version"), rs.getString("report_sha256"),
                rs.getBoolean("passed"), rs.getInt("case_count"),
                rs.getInt("scope_violation_count"), rs.getInt("current_snapshot_error_count"),
                rs.getInt("citation_ownership_error_count"), rs.getInt("degraded_case_count"),
                rs.getBoolean("ablation_complete"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    private void validate(RecordRequest request) {
        if (request == null || !FINAL_PLANS.contains(request.planVersion())) {
            throw invalid("Unsupported final retrieval plan");
        }
        if (blank(request.datasetVersion()) || blank(request.reportVersion())
                || request.reportSha256() == null || !request.reportSha256().matches("sha256:[0-9a-f]{64}")) {
            throw invalid("Quality receipt versions and SHA-256 are required");
        }
        if (request.caseCount() <= 0 || request.scopeViolationCount() < 0
                || request.currentSnapshotErrorCount() < 0
                || request.citationOwnershipErrorCount() < 0 || request.degradedCaseCount() < 0) {
            throw invalid("Quality receipt counts are invalid");
        }
        List<AblationResult> ablations = request.ablations() == null ? List.of() : request.ablations();
        Set<String> names = ablations.stream().map(AblationResult::variant).collect(Collectors.toSet());
        if (!names.equals(REQUIRED_ABLATIONS.get(request.planVersion())) || names.size() != ablations.size()) {
            throw invalid("Quality receipt ablation matrix is incomplete or duplicated");
        }
        for (AblationResult result : ablations) {
            if (!finiteUnit(result.recallAtK()) || !finiteUnit(result.mrr())
                    || !finiteUnit(result.ndcgAtK()) || !finiteUnit(result.citationCoverage())
                    || result.scopeViolationCount() < 0 || result.p95LatencyMicros() < 0) {
                throw invalid("Quality receipt ablation metrics are invalid");
            }
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException("RETRIEVAL_QUALITY_RECEIPT_INVALID", message, HttpStatus.BAD_REQUEST);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private boolean finiteUnit(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }

    public record RecordRequest(String planVersion, String datasetVersion, String reportVersion,
                                String reportSha256, boolean passed, int caseCount,
                                int scopeViolationCount, int currentSnapshotErrorCount,
                                int citationOwnershipErrorCount, int degradedCaseCount,
                                List<AblationResult> ablations) { }

    public record AblationResult(String variant, double recallAtK, double mrr, double ndcgAtK,
                                 double citationCoverage, int scopeViolationCount,
                                 long p95LatencyMicros) { }

    public record Receipt(String id, String workspaceId, String planVersion, String datasetVersion,
                          String reportVersion, String reportSha256, boolean passed, int caseCount,
                          int scopeViolationCount, int currentSnapshotErrorCount,
                          int citationOwnershipErrorCount, int degradedCaseCount,
                          boolean ablationComplete, String createdBy, Instant createdAt) {
        public boolean releaseEligible() {
            return passed && caseCount > 0 && scopeViolationCount == 0
                    && currentSnapshotErrorCount == 0 && citationOwnershipErrorCount == 0
                    && degradedCaseCount == 0 && ablationComplete;
        }
    }
}
