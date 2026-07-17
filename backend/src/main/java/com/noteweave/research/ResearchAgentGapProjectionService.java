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
        List<VerifierRepair> verifierRepairs = jdbcTemplate.query("""
                select id, target_id, reason_code, evidence_ids_json
                from research_verifier_decision
                where research_run_id = ? and decision_scope = 'CELL'
                  and decision_type = 'QUORUM_REPAIR_REQUIRED'
                  and action_text = 'COUNTERFACTUAL_REPAIR' and decision_status = 'OPEN'
                  and target_id is not null
                order by cast(id as binary)
                """, (rs, rowNum) -> new VerifierRepair(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), runId);
        for (VerifierRepair repair : verifierRepairs) {
            Aggregate aggregate = byCell.computeIfAbsent(repair.cellKey(), ignored -> new Aggregate());
            aggregate.decisionIds.add(repair.id());
            aggregate.statuses.add("VERIFIER_DECISION_OPEN");
            aggregate.reasons.add(repair.reasonCode());
            aggregate.excludedSources.addAll(evidenceSourceIds(runId, strings(repair.evidenceIdsJson())));
        }
        List<RepairGap> result = new ArrayList<>();
        for (Map.Entry<String, Aggregate> item : byCell.entrySet()) {
            CellState cell = loadCell(runId, item.getKey());
            if (cell == null) continue;
            if ("VERIFIED".equals(cell.status())) continue;
            Aggregate aggregate = item.getValue();
            result.add(new RepairGap(cell.cellKey(), cell.repairCount(), "FROZEN".equals(cell.status()), cell.activeTaskId() == null,
                    digest(item.getKey(), aggregate), List.copyOf(aggregate.taskIds),
                    List.copyOf(aggregate.decisionIds), List.copyOf(aggregate.excludedSources)));
        }
        return result.stream().sorted(Comparator.comparing(RepairGap::cellKey)).toList();
    }

    private CellState loadCell(String runId, String cellKey) {
        return jdbcTemplate.query("""
                select cell_key, cell_status, repair_count, active_task_id
                from research_cell where research_run_id = ? and cell_key = ?
                """, rs -> rs.next() ? new CellState(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)) : null, runId, cellKey);
    }

    private List<String> cells(String json) {
        return strings(json);
    }

    private List<String> strings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() { }); }
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
                            List<String> excludedSourceIds) { }
    private record FailedTask(String id, String status, String terminalReason, String targetCellsJson, String executionContextJson) { }
    private record VerifierRepair(String id, String cellKey, String reasonCode, String evidenceIdsJson) { }
    private record CellState(String cellKey, String status, int repairCount, String activeTaskId) { }
    private static final class Aggregate {
        private final Set<String> taskIds = new LinkedHashSet<>();
        private final Set<String> decisionIds = new LinkedHashSet<>();
        private final Set<String> statuses = new LinkedHashSet<>();
        private final Set<String> reasons = new LinkedHashSet<>();
        private final Set<String> excludedSources = new LinkedHashSet<>();
    }
}
