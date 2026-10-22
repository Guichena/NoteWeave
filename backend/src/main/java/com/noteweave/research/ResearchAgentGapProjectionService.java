package com.noteweave.research;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Derives repair facts exclusively from persisted failed/expired task snapshots. */
@Service
public class ResearchAgentGapProjectionService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ResearchAgentGapProjectionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<RepairGap> projectRepairableGaps(String runId, int waveNo) {
        if (runId == null || runId.isBlank() || waveNo < 1) {
            throw new BusinessException("RESEARCH_AGENT_GAP_PROJECTION_INVALID", "Run and positive wave are required");
        }
        List<FailedTask> failed = jdbcTemplate.query("""
                select id, status, coalesce(terminal_reason, ''), target_cells_json, execution_context_json
                from research_agent_task
                where research_run_id = ? and wave_no = ? and status in ('FAILED', 'EXPIRED')
                order by cast(id as binary)
                """, (rs, rowNum) -> new FailedTask(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), runId, waveNo);
        Map<String, Aggregate> byCell = new LinkedHashMap<>();
        for (FailedTask task : failed) {
            for (String cellKey : cells(task.targetCellsJson())) {
                Aggregate aggregate = byCell.computeIfAbsent(cellKey, ignored -> new Aggregate());
                aggregate.taskIds.add(task.id());
                aggregate.statuses.add(task.status());
                aggregate.reasons.add(task.terminalReason());
                aggregate.excludedSources.addAll(sourceIds(task.executionContextJson()));
            }
        }
        // 合并任务只要有一个单元格拿到候选就算成功，其余单元格留在 GAP 也不会进入失败任务的补查；
        // 失败任务所在轮次被证据审计推过去后同样看不到。这些单元格按研究范围各补查一次。
        for (String cellKey : unrepairedGapCellKeys(jdbcTemplate, runId)) {
            Aggregate aggregate = byCell.computeIfAbsent(cellKey, ignored -> new Aggregate());
            for (FailedTask task : evidenceTasksFor(runId, cellKey)) {
                aggregate.taskIds.add(task.id());
                aggregate.statuses.add(task.status());
                if ("FAILED".equals(task.status()) && !task.terminalReason().isBlank()) aggregate.reasons.add(task.terminalReason());
                aggregate.excludedSources.addAll(sourceIds(task.executionContextJson()));
            }
            aggregate.reasons.add(GAP_REPAIR_REASON);
        }
        List<VerifierRepair> verifierRepairs = jdbcTemplate.query("""
                select id, target_id, reason_code, evidence_ids_json
                from research_verifier_decision
                where research_run_id = ? and decision_scope = 'CELL'
                  and decision_type in %s
                  and action_text = 'COUNTERFACTUAL_REPAIR' and decision_status = 'OPEN'
                  and target_id is not null
                order by cast(id as binary)
                """.formatted(ResearchAuditRepairDecisions.TYPES), (rs, rowNum) -> new VerifierRepair(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), runId);
        for (VerifierRepair repair : verifierRepairs) {
            Aggregate aggregate = byCell.computeIfAbsent(repair.cellKey(), ignored -> new Aggregate());
            aggregate.decisionIds.add(repair.id());
            aggregate.statuses.add("VERIFIER_DECISION_OPEN");
            aggregate.reasons.add(repair.reasonCode());
            if ("SEMANTIC_EQUIVALENCE_APPROVED".equals(repair.reasonCode())) aggregate.excludedSources.clear();
            else aggregate.excludedSources.addAll(evidenceSourceIds(runId, strings(repair.evidenceIdsJson())));
        }
        List<RepairGap> result = new ArrayList<>();
        for (Map.Entry<String, Aggregate> item : byCell.entrySet()) {
            CellState cell = loadCell(runId, item.getKey());
            if (cell == null) continue;
            if ("VERIFIED".equals(cell.status()) && Set.of("PASS","NOT_REQUIRED").contains(cell.auditStatus())) continue;
            Aggregate aggregate = item.getValue();
            result.add(new RepairGap(cell.cellKey(), cell.repairCount(), "FROZEN".equals(cell.status()), cell.activeTaskId() == null,
                    digest(item.getKey(), aggregate), List.copyOf(aggregate.taskIds),
                    List.copyOf(aggregate.decisionIds), List.copyOf(aggregate.excludedSources),
                    aggregate.reasons.stream().sorted().toList()));
        }
        return result.stream().sorted(Comparator.comparing(RepairGap::cellKey)).toList();
    }

    static final String GAP_REPAIR_REASON = "NO_SUPPORTED_CANDIDATE";
    /** 每轮最多补查的缺口单元格数，规模大的研究分几轮补，避免一次派出过多任务。 */
    static final int GAP_REPAIR_MAX_CELLS = 8;

    /**
     * 已经研究过但仍然缺少结论的单元格；需求覆盖不足的任务在两次补查上限内继续累计。
     * 快照计数与补查投影共用这一查询，触发条件和实际补查目标不会不一致。
     */
    static List<String> unrepairedGapCellKeys(JdbcTemplate jdbcTemplate, String runId) {
        int maximum = repairLimit(jdbcTemplate, runId);
        List<String> cells = jdbcTemplate.query("""
                select cell.cell_key from research_cell cell
                where cell.research_run_id = ? and cell.cell_status = 'GAP'
                  and (cell.repair_count = 0 or (cell.repair_count < ? and (cell.last_verifier_decision = 'REQUIREMENT_FACT_DEDUPLICATED' or exists (
                    select 1 from research_cell_merge coverage_merge
                    where coverage_merge.research_run_id = cell.research_run_id and coverage_merge.cell_key = cell.cell_key
                      and coverage_merge.reason_code = 'REQUIREMENT_COVERAGE_INCOMPLETE'
                  )))) and cell.active_task_id is null
                  and exists (
                    select 1 from research_agent_task task
                    where task.research_run_id = cell.research_run_id
                      and task.role in ('DEEP_CELL', 'COUNTERFACTUAL')
                      and task.status in ('SUBMITTED', 'FAILED')
                      and task.target_cells_json like concat('%"', cell.cell_key, '"%')
                  )
                  and not exists (
                    select 1 from research_agent_task task
                    where task.research_run_id = cell.research_run_id
                      and task.status not in ('SUBMITTED', 'FAILED', 'CANCELLED')
                      and task.target_cells_json like concat('%"', cell.cell_key, '"%')
                  )
                  and not exists (
                    select 1 from research_verifier_decision decision
                    where decision.research_run_id = cell.research_run_id
                      and decision.target_id = cell.cell_key
                      and decision.decision_status = 'OPEN'
                  )
                order by cell.cell_key
                limit ?
                """, (rs, rowNum) -> rs.getString(1), runId, maximum,
                new ResearchRequirementCoverageService(jdbcTemplate,new ObjectMapper()).fullCompletion(runId) ? 4096 : GAP_REPAIR_MAX_CELLS);
        Map<String,Integer> scores = ResearchCompletionScheduler.scores(jdbcTemplate,new ObjectMapper(),runId);
        return cells.stream().sorted(Comparator.<String>comparingInt(cell -> scores.getOrDefault(cell,0)).reversed().thenComparing(Comparator.naturalOrder()))
                .limit(GAP_REPAIR_MAX_CELLS).toList();
    }

    static int repairLimit(JdbcTemplate jdbc, String runId) {
        if (!new ResearchRequirementCoverageService(jdbc, new ObjectMapper()).improvedCompletion(runId)) return 2;
        boolean full = new ResearchRequirementCoverageService(jdbc,new ObjectMapper()).fullCompletion(runId);
        Integer minimum = jdbc.queryForObject("""
                select coalesce(max(%s), 1) from research_requirement requirement
                join research_cell cell on cell.research_run_id = requirement.research_run_id and cell.cell_key = requirement.cell_key
                  and cell.plan_revision = requirement.plan_revision and cell.entity_set_version = requirement.entity_set_version
                where requirement.research_run_id = ? and requirement.completion_mode = 'DISTINCT_FACTS'
                """.formatted(full ? "requirement.minimum_count * case when cell.high_risk then 2 else 1 end" : "requirement.minimum_count"), Integer.class,runId);
        return Math.min(full ? 8 : 4, Math.max(2, ((minimum == null ? 1 : minimum) + 5) / 6));
    }

    int repairLimit(String runId) { return repairLimit(jdbcTemplate, runId); }

    private List<FailedTask> evidenceTasksFor(String runId, String cellKey) {
        return jdbcTemplate.query("""
                select id, status, coalesce(terminal_reason, ''), target_cells_json, execution_context_json
                from research_agent_task
                where research_run_id = ? and role in ('DEEP_CELL', 'COUNTERFACTUAL')
                  and status in ('SUBMITTED', 'FAILED')
                  and target_cells_json like concat('%"', ?, '"%')
                order by cast(id as binary)
                """, (rs, rowNum) -> new FailedTask(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                runId, cellKey);
    }

    private CellState loadCell(String runId, String cellKey) {
        return jdbcTemplate.query("""
                select cell_key, cell_status, repair_count, active_task_id, semantic_audit_status
                from research_cell where research_run_id = ? and cell_key = ?
                """, rs -> rs.next() ? new CellState(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5)) : null, runId, cellKey);
    }

    private List<String> cells(String json) {
        return strings(json);
    }

    private List<String> strings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<List<String>>() { });
            if (values == null || values.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("invalid string list");
            }
            return values;
        }
        catch (Exception exception) { throw new BusinessException("RESEARCH_AGENT_GAP_PROJECTION_INVALID", "Task target snapshot is invalid"); }
    }

    private Set<String> evidenceSourceIds(String runId, List<String> evidenceKeys) {
        if (evidenceKeys.isEmpty()) return Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(evidenceKeys.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(runId);
        parameters.addAll(evidenceKeys);
        return new LinkedHashSet<>(jdbcTemplate.query("""
                select distinct source_id from source_evidence
                where research_run_id = ? and evidence_key in (""" + placeholders + ") and source_id is not null order by source_id",
                (rs, rowNum) -> rs.getString(1), parameters.toArray()));
    }

    @SuppressWarnings("unchecked")
    private Set<String> sourceIds(String json) {
        if (json == null || json.isBlank()) return Set.of();
        try {
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
            Object policy = root.get("source_policy");
            if (!(policy instanceof Map<?, ?> map) || !(map.get("source_scope") instanceof List<?> scopes)) return Set.of();
            Set<String> ids = new LinkedHashSet<>();
            for (Object scope : scopes) if (scope instanceof Map<?, ?> source && source.get("source_id") != null) ids.add(String.valueOf(source.get("source_id")));
            return ids;
        } catch (Exception exception) { throw new BusinessException("RESEARCH_AGENT_GAP_PROJECTION_INVALID", "Task execution snapshot is invalid"); }
    }

    private String digest(String cellKey, Aggregate aggregate) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("cell_key", cellKey); canonical.put("task_ids", aggregate.taskIds.stream().sorted().toList());
            canonical.put("verifier_decision_ids", aggregate.decisionIds.stream().sorted().toList());
            canonical.put("statuses", aggregate.statuses.stream().sorted().toList()); canonical.put("reasons", aggregate.reasons.stream().sorted().toList());
            canonical.put("excluded_source_ids", aggregate.excludedSources.stream().sorted().toList());
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(Json.write(objectMapper, canonical).getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder("sha256:"); for (byte value : bytes) output.append(String.format("%02x", value)); return output.toString();
        } catch (Exception exception) { throw new IllegalStateException("Cannot digest repair gap", exception); }
    }

    public record RepairGap(String cellKey, int repairCount, boolean frozen, boolean noActiveTask,
                            String reasonDigest, List<String> failedTaskIds, List<String> verifierDecisionIds,
                            List<String> excludedSourceIds, List<String> reasonCodes) { }
    private record FailedTask(String id, String status, String terminalReason, String targetCellsJson, String executionContextJson) { }
    private record VerifierRepair(String id, String cellKey, String reasonCode, String evidenceIdsJson) { }
    private record CellState(String cellKey, String status, int repairCount, String activeTaskId, String auditStatus) { }
    private static final class Aggregate {
        private final Set<String> taskIds = new LinkedHashSet<>();
        private final Set<String> decisionIds = new LinkedHashSet<>();
        private final Set<String> statuses = new LinkedHashSet<>();
        private final Set<String> reasons = new LinkedHashSet<>();
        private final Set<String> excludedSources = new LinkedHashSet<>();
    }
}
