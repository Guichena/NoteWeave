package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Persists the plan revision produced by a bounded, cell-local MA4 repair. */
@Service
class ResearchAgentLocalReplanRecorder {
    private static final String PLANNER_VERSION_PREFIX = "local-replan.v1:r";
    private static final String DIGEST_DOMAIN = "research-local-replan.v1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentCompletionCanonicalizer canonicalizer;
    private final ResearchAgentReplanAuditService audits;

    ResearchAgentLocalReplanRecorder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                     ResearchAgentCompletionCanonicalizer canonicalizer,
                                     ResearchAgentReplanAuditService audits) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
        this.audits = audits;
    }

    void record(String runId, int revisionFrom, String decisionDigest, String repairCause,
                List<ResearchAgentRepairStopPolicy.RepairTarget> targets) {
        if (targets.isEmpty()) return;
        PlanRow previous = latestPlan(runId);
        // Old synthetic MA4 fixtures and pre-matrix runs have no durable plan to revise. The
        // production Table-as-State path always has one; do not fabricate an old digest for legacy
        // rows merely to make an audit record look complete.
        if (previous == null) return;

        int revisionTo = revisionFrom + 1;
        List<String> affectedCells = targets.stream()
                .map(ResearchAgentRepairStopPolicy.RepairTarget::cellKey).sorted().toList();
        Map<String, Object> reasons = new LinkedHashMap<>();
        targets.stream().sorted(java.util.Comparator.comparing(ResearchAgentRepairStopPolicy.RepairTarget::cellKey))
                .forEach(target -> reasons.put(target.cellKey(), target.reasonDigest()));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schema_version", "research-local-replan.v1");
        plan.put("revision_from", revisionFrom);
        plan.put("revision_to", revisionTo);
        plan.put("affected_cells", affectedCells);
        plan.put("reason_digests", reasons);
        plan.put("repair_cause", repairCause);
        plan.put("selected_repair", "COUNTERFACTUAL_RESEARCH");
        plan.put("decision_digest", decisionDigest);
        String planDigest = canonicalizer.domainSeparatedDigest(DIGEST_DOMAIN, plan);

        jdbcTemplate.update("""
                insert into research_matrix_plan(
                    id, research_run_id, planner_version, plan_mode, plan_status,
                    row_count, column_count, cell_count, bounded, reason_codes_json,
                    plan_json, plan_digest)
                values (?, ?, ?, 'LOCAL_REPAIR', 'ACTIVE', ?, ?, ?, ?, ?, ?, ?)
                """, Ids.newId(), runId, PLANNER_VERSION_PREFIX + revisionTo,
                previous.rowCount(), previous.columnCount(), previous.cellCount(), previous.bounded(),
                Json.write(objectMapper, List.of("CELL_LOCAL_REPAIR")), Json.write(objectMapper, plan), planDigest);

        for (String cell : affectedCells) {
            jdbcTemplate.update("""
                    update research_cell set plan_revision = ?, updated_at = current_timestamp
                    where research_run_id = ? and cell_key = ? and plan_revision = ?
                    """, revisionTo, runId, cell, revisionFrom);
        }
        long targetCount = affectedCells.size();
        Map<String, Long> budgetDelta = ResearchAgentTaskCoordinatorService.deepCellBudget((int) targetCount);
        audits.recordReplan(new ResearchAgentReplanAuditService.ReplanAuditCommand(
                runId, revisionFrom, revisionTo, previous.planDigest(), planDigest,
                deviationType(repairCause),
                "MA4 observed " + repairCause + " for " + targetCount
                        + " unresolved cell(s); only those cells were replanned.",
                affectedCells, budgetDelta, null, "COUNTERFACTUAL_RESEARCH"));
    }

    private String deviationType(String repairCause) {
        if ("EVIDENCE_CONFLICT".equals(repairCause)) return ResearchReplanDeviationTypes.EVIDENCE_CONFLICT;
        if ("FETCH_FAILED".equals(repairCause) || "PROVIDER_FAILED".equals(repairCause)) {
            return ResearchReplanDeviationTypes.PROVIDER_FAILURE;
        }
        return ResearchReplanDeviationTypes.MARGINAL_GAIN_EXHAUSTED;
    }

    private PlanRow latestPlan(String runId) {
        return jdbcTemplate.query("""
                select row_count, column_count, cell_count, bounded, plan_digest
                from research_matrix_plan where research_run_id = ?
                order by created_at desc, id desc limit 1
                """, rs -> rs.next() ? new PlanRow(
                rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getBoolean(4), rs.getString(5)) : null, runId);
    }

    private record PlanRow(int rowCount, int columnCount, int cellCount, boolean bounded, String planDigest) { }
}
