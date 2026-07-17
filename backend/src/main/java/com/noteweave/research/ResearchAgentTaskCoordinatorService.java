package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MA4E server-side coordinator.  It is intentionally not called from the
 * legacy run outbox: callers must opt a run into INCREMENTAL_V1 first.
 */
@Service
public class ResearchAgentTaskCoordinatorService {
    private static final int MAX_CELLS_PER_TASK = 3;
    private static final int MAX_EVIDENCE_CARDS_PER_CELL = 4;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentTaskService taskService;
    private final ResearchBudgetAndCheckpointService budgetService;
    private final ResearchAgentCommandOutboxService outboxService;

    public ResearchAgentTaskCoordinatorService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                               ResearchAgentTaskService taskService,
                                               ResearchBudgetAndCheckpointService budgetService,
                                               ResearchAgentCommandOutboxService outboxService) {
        this.jdbcTemplate = jdbcTemplate; this.objectMapper = objectMapper; this.taskService = taskService;
        this.budgetService = budgetService; this.outboxService = outboxService;
    }

    @Transactional
    public CoordinatorReceipt planAndEnqueue(String runId) {
        return planAndEnqueueForWave(runId, 1);
    }

    /** Taskizes one coordinator-authorized wave; MA4I ownership is supplied by the caller. */
    @Transactional
    public CoordinatorReceipt planAndEnqueueForWave(String runId, int waveNo) {
        if (waveNo < 1) throw new BusinessException("RESEARCH_AGENT_COORDINATOR_WAVE_INVALID", "Wave number must be positive");
        RunScope run = requireIncrementalRunnableRun(runId);
        List<Map<String, Object>> sources = loadTrustedSources(run.workspaceId(), run.sourceScopeJson());
        if (sources.isEmpty()) {
            throw new BusinessException("RESEARCH_AGENT_COORDINATOR_SOURCE_SCOPE_EMPTY",
                    "Incremental taskization requires ready workspace sources");
        }
        List<CellScope> cells = jdbcTemplate.query("""
                select cell_key, cell_version, plan_revision, entity_set_version, high_risk, branch_id
                from research_cell
                where research_run_id = ? and cell_status not in ('FROZEN', 'VERIFIED') and active_task_id is null
                order by cell_key
                """, (rs, rowNum) -> new CellScope(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getBoolean(5), rs.getString(6)), runId);
        int created = 0;
        int replayed = 0;
        int enqueued = 0;
        for (List<CellScope> bundle : bundles(cells)) {
            CellScope first = bundle.get(0);
            String entityId = entityId(first.cellKey());
            if (bundle.stream().anyMatch(cell -> !entityId(cell.cellKey()).equals(entityId)
                    || cell.planRevision() != first.planRevision() || cell.entitySetVersion() != first.entitySetVersion()
                    || !java.util.Objects.equals(cell.branchId(), first.branchId()))) {
                throw new BusinessException("RESEARCH_AGENT_COORDINATOR_SCOPE_INVALID", "Task bundle mixes entity or plan scope");
            }
            String logicalFingerprint = fingerprint(run.id(), waveNo, entityId, first.planRevision(), first.entitySetVersion(), bundle);
            int candidateQuorum = first.highRisk() ? 2 : 1;
            String quorumGroupKey = first.highRisk() ? "quorum:" + logicalFingerprint : null;
            for (int candidateSlot = 1; candidateSlot <= candidateQuorum; candidateSlot++) {
                String slotFingerprint = candidateQuorum == 1
                        ? logicalFingerprint : logicalFingerprint + ":slot:" + candidateSlot;
                String role = candidateSlot == 1 ? "DEEP_CELL" : "COUNTERFACTUAL";
                List<Map<String, Object>> slotSources = sourcesForSlot(sources, candidateQuorum, candidateSlot);
                Map<String, Long> reservation = deepCellBudget(bundle.size());
                Map<String, Object> snapshotBudget = new LinkedHashMap<>(reservation);
                ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                        run.id(), "deep-cell:" + slotFingerprint, "coordinator:" + slotFingerprint, waveNo, role, entityId,
                        first.branchId() == null ? "branch-main" : first.branchId(),
                        first.planRevision(), first.entitySetVersion(),
                        bundle.stream().map(CellScope::cellKey).toList(), snapshotBudget,
                        bundle.stream().map(cell -> new ResearchAgentTaskService.TargetCellBinding(cell.cellKey(), cell.version())).toList(),
                        new ResearchAgentTaskService.TaskExecutionContext("research-default",
                                Map.of("source_scope", slotSources,
                                        "allow_external_search", false,
                                        "allow_external_fetch", false),
                                Map.of("query", run.question()))
                ));
                jdbcTemplate.update("""
                        update research_agent_task
                        set logical_task_key = ?, quorum_group_key = ?, candidate_quorum = ?, candidate_slot = ?,
                            snapshot_schema_version = 'research-agent-task-snapshot.v2', snapshot_digest = null
                        where id = ?
                        """, "deep-cell:" + logicalFingerprint, quorumGroupKey, candidateQuorum, candidateSlot, task.taskId());
                boolean existed = jdbcTemplate.queryForObject(
                        "select count(*) from research_budget_reservation where research_agent_task_id = ?",
                        Integer.class, task.taskId()) > 0;
                budgetService.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                        run.id(), task.taskId(), "reserve:" + slotFingerprint, reservation));
                ResearchAgentCommandOutboxService.CommandReceipt command = outboxService.enqueue(task.taskId());
                if (existed) replayed++; else created++;
                if (!command.idempotentReplay()) enqueued++;
            }
        }
        return new CoordinatorReceipt(created, replayed, enqueued, cells.size());
    }

    /**
     * Creates one independently-scoped COUNTERFACTUAL task per policy target.
     * The caller supplies only a prior policy result; all mutable target and
     * source authority is re-derived under the run lock here.
     */
    @Transactional
    public CoordinatorReceipt planCounterfactualRepairs(CounterfactualRepairCommand command) {
        if (command == null || command.waveNo() < 1 || command.parentCheckpointSeq() < 0
                || command.targets() == null || command.targets().isEmpty()) {
            throw new BusinessException("RESEARCH_AGENT_REPAIR_INVALID", "Counterfactual repair contract is invalid");
        }
        RunScope run = requireIncrementalRunnableRun(command.researchRunId());
        List<Map<String, Object>> sources = loadTrustedSources(run.workspaceId(), run.sourceScopeJson());
        if (sources.isEmpty()) throw new BusinessException("RESEARCH_AGENT_COORDINATOR_SOURCE_SCOPE_EMPTY", "Counterfactual repair requires ready workspace sources");
        List<CounterfactualTarget> targets = command.targets().stream()
                .sorted(java.util.Comparator.comparing(CounterfactualTarget::cellKey).thenComparing(CounterfactualTarget::reasonDigest))
                .toList();
        if (targets.stream().map(CounterfactualTarget::cellKey).distinct().count() != targets.size()) {
            throw new BusinessException("RESEARCH_AGENT_REPAIR_INVALID", "Counterfactual target cells must be unique");
        }
        int created = 0;
        int replayed = 0;
        int enqueued = 0;
        for (CounterfactualTarget target : targets) {
            CellScope cell = lockRepairableCell(run.id(), target.cellKey());
            List<Map<String, Object>> independentSources = sources.stream()
                    .filter(source -> !target.excludedSourceIds().contains(String.valueOf(source.get("source_id"))))
                    .toList();
            if (independentSources.isEmpty()) {
                throw new BusinessException("RESEARCH_AGENT_REPAIR_SOURCE_SCOPE_EMPTY", "Counterfactual repair has no independent source scope");
            }
            String fingerprint = repairFingerprint(run.id(), command.parentCheckpointSeq(), command.waveNo(), cell, target);
            Map<String, Object> snapshotBudget = new LinkedHashMap<>(deepCellBudget(1));
            ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                    run.id(), "counterfactual:" + fingerprint, "repair:" + fingerprint, command.waveNo(), "COUNTERFACTUAL",
                    entityId(cell.cellKey()), "branch-counterfactual", cell.planRevision(), cell.entitySetVersion(),
                    List.of(cell.cellKey()), snapshotBudget,
                    List.of(new ResearchAgentTaskService.TargetCellBinding(cell.cellKey(), cell.version())),
                    new ResearchAgentTaskService.TaskExecutionContext("research-default",
                            Map.of("source_scope", independentSources, "excluded_source_ids", target.excludedSourceIds(),
                                    "allow_external_search", false, "allow_external_fetch", false),
                            Map.of("query", run.question(), "repair_reason_digest", target.reasonDigest(),
                                    "parent_checkpoint_seq", command.parentCheckpointSeq()))
            ));
            jdbcTemplate.update("""
                    update research_agent_task
                    set logical_task_key = ?, quorum_group_key = null, candidate_quorum = 1, candidate_slot = 1,
                        snapshot_schema_version = 'research-agent-task-snapshot.v2', snapshot_digest = null,
                        updated_at = current_timestamp
                    where id = ?
                    """, "counterfactual:" + fingerprint, task.taskId());
            boolean existed = jdbcTemplate.queryForObject("select count(*) from research_budget_reservation where research_agent_task_id = ?", Integer.class, task.taskId()) > 0;
            budgetService.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                    run.id(), task.taskId(), "repair-reserve:" + fingerprint, deepCellBudget(1)));
            ResearchAgentCommandOutboxService.CommandReceipt outbox = outboxService.enqueue(task.taskId());
            if (existed) {
                replayed++;
            } else {
                int updated = jdbcTemplate.update("""
                        update research_cell set repair_count = repair_count + 1, updated_at = current_timestamp
                        where research_run_id = ? and cell_key = ? and cell_version = ? and active_task_id is null
                        """, run.id(), cell.cellKey(), cell.version());
                if (updated != 1) throw new BusinessException("RESEARCH_AGENT_REPAIR_TARGET_INVALID", "Counterfactual target changed while taskizing");
                created++;
            }
            if (!outbox.idempotentReplay()) enqueued++;
        }
        return new CoordinatorReceipt(created, replayed, enqueued, targets.size());
    }

    private RunScope requireIncrementalRunnableRun(String runId) {
        RunScope run = jdbcTemplate.query("""
                select id, workspace_id, question, source_scope_json, status, agent_execution_mode
                from research_run where id = ? for update
                """, rs -> rs.next() ? new RunScope(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)) : null, runId);
        if (run == null) throw new BusinessException("RESEARCH_AGENT_RUN_NOT_FOUND", "Research run does not exist");
        if (!"INCREMENTAL_V1".equals(run.executionMode())) throw new BusinessException("RESEARCH_AGENT_COORDINATOR_MODE_INVALID", "Task coordinator requires INCREMENTAL_V1");
        if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(run.status())) throw new BusinessException("RESEARCH_AGENT_RUN_TERMINAL", "Cannot taskize terminal run");
        return run;
    }

    private CellScope lockRepairableCell(String runId, String cellKey) {
        if (cellKey == null || cellKey.isBlank()) {
            throw new BusinessException("RESEARCH_AGENT_REPAIR_INVALID", "Counterfactual target cell is required");
        }
        CellScope cell = jdbcTemplate.query("""
                select cell_key, cell_version, plan_revision, entity_set_version, high_risk, branch_id
                from research_cell
                where research_run_id = ? and cell_key = ? and cell_status not in ('FROZEN', 'VERIFIED') and active_task_id is null
                for update
                """, rs -> rs.next() ? new CellScope(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getBoolean(5), rs.getString(6)) : null,
                runId, cellKey);
        if (cell == null) throw new BusinessException("RESEARCH_AGENT_REPAIR_TARGET_INVALID", "Counterfactual target is foreign, frozen, verified, or active");
        return cell;
    }

    private List<Map<String, Object>> loadTrustedSources(String workspaceId, String rawScope) {
        List<String> ids;
        try { ids = objectMapper.readValue(rawScope, new TypeReference<List<String>>() { }); }
        catch (JsonProcessingException exception) { throw new BusinessException("RESEARCH_AGENT_COORDINATOR_SOURCE_SCOPE_INVALID", "Run source scope is invalid"); }
        if (ids == null || ids.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String sourceId : ids) {
            Map<String, Object> source = jdbcTemplate.query("""
                    select s.id, s.title, s.source_type, coalesce(s.summary, '') as summary,
                      coalesce((select sw.content from source_chunk sc join source_window sw on sw.source_chunk_id = sc.id
                        where sc.source_id = s.id order by sc.chunk_no, sw.window_no limit 1), '') as sample_text
                    from source s where s.id = ? and s.workspace_id = ? and s.status = 'READY'
                    """, rs -> rs.next() ? sourceMap(rs) : null, sourceId, workspaceId);
            if (source == null || String.valueOf(source.get("sample_text")).isBlank()) {
                throw new BusinessException("RESEARCH_AGENT_COORDINATOR_SOURCE_SCOPE_INVALID", "Run source scope contains no ready source sample");
            }
            result.add(source);
        }
        return result;
    }

    private Map<String, Object> sourceMap(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source_id", rs.getString("id")); value.put("title", rs.getString("title"));
        value.put("source_type", rs.getString("source_type")); value.put("summary", rs.getString("summary"));
        value.put("sample_text", rs.getString("sample_text")); return value;
    }
    private List<List<CellScope>> bundles(List<CellScope> cells) {
        Map<String, List<CellScope>> byEntity = new LinkedHashMap<>();
        for (CellScope cell : cells) byEntity.computeIfAbsent(entityId(cell.cellKey()), ignored -> new ArrayList<>()).add(cell);
        List<List<CellScope>> result = new ArrayList<>();
        for (List<CellScope> group : byEntity.values()) {
            List<CellScope> normal = new ArrayList<>();
            for (CellScope cell : group) {
                if (cell.highRisk()) {
                    appendNormalBundles(result, normal);
                    normal.clear();
                    result.add(List.of(cell));
                } else {
                    normal.add(cell);
                }
            }
            appendNormalBundles(result, normal);
        }
        return result;
    }
    private void appendNormalBundles(List<List<CellScope>> result, List<CellScope> cells) {
        for (int i = 0; i < cells.size(); i += MAX_CELLS_PER_TASK) {
            result.add(List.copyOf(cells.subList(i, Math.min(cells.size(), i + MAX_CELLS_PER_TASK))));
        }
    }
    private List<Map<String, Object>> sourcesForSlot(
            List<Map<String, Object>> sources, int candidateQuorum, int candidateSlot
    ) {
        if (candidateQuorum == 1 || sources.size() < 2) return sources;
        List<Map<String, Object>> sorted = sources.stream().sorted(java.util.Comparator.comparing(
                source -> String.valueOf(source.get("source_id")), ResearchAgentBinaryOrder.UTF8)).toList();
        List<Map<String, Object>> selected = new ArrayList<>();
        for (int index = candidateSlot - 1; index < sorted.size(); index += candidateQuorum) {
            selected.add(sorted.get(index));
        }
        if (selected.isEmpty()) {
            throw new BusinessException("RESEARCH_AGENT_COORDINATOR_SOURCE_SCOPE_EMPTY",
                    "High-risk candidate slot has no trusted source partition");
        }
        return List.copyOf(selected);
    }
    private String entityId(String cellKey) { int separator = cellKey.indexOf(':'); if (separator < 1) throw new BusinessException("RESEARCH_AGENT_COORDINATOR_SCOPE_INVALID", "Cell key has no entity scope"); return cellKey.substring(0, separator); }
    private Map<String, Long> deepCellBudget(int targetCellCount) {
        long cells = Math.max(1, targetCellCount);
        long evidence = cells * MAX_EVIDENCE_CARDS_PER_CELL;
        return Map.of(
                "llm_calls", 1L,
                "search_calls", 1L,
                "fetch_calls", 1L,
                "read_calls", 1L,
                "extract_calls", 1L,
                "evidence_cards", evidence,
                "evidence_appended", evidence,
                "candidates_submitted", cells,
                "candidate_merges_accepted", cells,
                "candidate_merges_rejected", cells);
    }
    private String fingerprint(String runId, int waveNo, String entityId, int planRevision, int entitySetVersion, List<CellScope> cells) {
        try { String text = runId + "|wave@" + waveNo + "|" + entityId + "|plan@" + planRevision + "|entities@" + entitySetVersion
                + cells.stream().map(cell -> "|" + cell.cellKey() + "@" + cell.version()).reduce("", String::concat);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); StringBuilder output = new StringBuilder(); for (byte item : hash) output.append(String.format("%02x", item)); return output.substring(0, 32); }
        catch (Exception exception) { throw new IllegalStateException("Cannot create task fingerprint", exception); }
    }
    private String repairFingerprint(String runId, int parentCheckpointSeq, int waveNo, CellScope cell, CounterfactualTarget target) {
        try {
            String text = runId + "|parent@" + parentCheckpointSeq + "|wave@" + waveNo + "|counterfactual|"
                    + cell.cellKey() + "@" + cell.version() + "|plan@" + cell.planRevision() + "|entities@" + cell.entitySetVersion()
                    + "|reason@" + target.reasonDigest() + target.excludedSourceIds().stream().sorted().map(id -> "|exclude@" + id).reduce("", String::concat);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            for (byte item : hash) output.append(String.format("%02x", item));
            return output.substring(0, 32);
        } catch (Exception exception) { throw new IllegalStateException("Cannot create repair task fingerprint", exception); }
    }
    public record CoordinatorReceipt(int createdTaskCount, int idempotentReplayCount, int enqueuedCommandCount, int scopedCellCount) { }
    public record CounterfactualRepairCommand(String researchRunId, int parentCheckpointSeq, int waveNo,
                                              List<CounterfactualTarget> targets) { }
    public record CounterfactualTarget(String cellKey, String reasonDigest, List<String> excludedSourceIds) {
        public CounterfactualTarget {
            excludedSourceIds = excludedSourceIds == null ? List.of() : excludedSourceIds.stream()
                    .filter(value -> value != null && !value.isBlank()).sorted().toList();
            if (reasonDigest == null || reasonDigest.isBlank()) {
                throw new BusinessException("RESEARCH_AGENT_REPAIR_INVALID", "Counterfactual repair reason digest is required");
            }
        }
    }
    private record RunScope(String id, String workspaceId, String question, String sourceScopeJson, String status, String executionMode) { }
    private record CellScope(String cellKey, int version, int planRevision, int entitySetVersion,
                             boolean highRisk, String branchId) { }
}
