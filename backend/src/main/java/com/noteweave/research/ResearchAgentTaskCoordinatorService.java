package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MA4E server-side coordinator.  It is intentionally not called from the
 * legacy run outbox: callers must opt a run into INCREMENTAL_V1 first.
 */
@Service
public class ResearchAgentTaskCoordinatorService {
    // A DeepCell extraction is only reliable when the LLM has one authoritative
    // target field. Bundling several schema columns lets one salient field consume
    // the whole response and leaves sibling Cells without candidates.
    private static final int MAX_CELLS_PER_TASK = 1;
    // Must match deep_cell_executor.MAX_EVIDENCE_CARDS_PER_CELL. The worker
    // deterministically caps admitted evidence before building candidates.
    private static final int MAX_EVIDENCE_CARDS_PER_CELL = 6;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentTaskService taskService;
    private final ResearchBudgetAndCheckpointService budgetService;
    private final ResearchAgentCommandOutboxService outboxService;
    private final ResearchAgentExternalEvidencePolicy externalEvidencePolicy;
    private final ResearchBriefCompiler researchBriefCompiler;

    public ResearchAgentTaskCoordinatorService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                               ResearchAgentTaskService taskService,
                                               ResearchBudgetAndCheckpointService budgetService,
                                               ResearchAgentCommandOutboxService outboxService,
                                               ResearchAgentExternalEvidencePolicy externalEvidencePolicy,
                                               ResearchBriefCompiler researchBriefCompiler) {
        this.jdbcTemplate = jdbcTemplate; this.objectMapper = objectMapper; this.taskService = taskService;
        this.budgetService = budgetService; this.outboxService = outboxService;
        this.externalEvidencePolicy = externalEvidencePolicy;
        this.researchBriefCompiler = researchBriefCompiler;
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
        ResearchBriefCompiler.CompiledBrief brief = researchBriefCompiler.compile(run.id(), run.question());
        List<Map<String, Object>> sources = loadTrustedSources(
                run.workspaceId(), run.sourceScopeJson(), run.retrievalMode());
        List<CellScope> cells = jdbcTemplate.query("""
                select cell_key, cell_version, plan_revision, entity_set_version, high_risk, branch_id
                from research_cell
                where research_run_id = ? and cell_status in ('GAP', 'STALE', 'CANDIDATE_READY') and active_task_id is null
                  and not exists (
                    select 1 from research_verifier_decision decision
                    where decision.research_run_id = research_cell.research_run_id
                      and decision.target_id = research_cell.cell_key
                      and decision.decision_status = 'OPEN'
                      and decision.decision_type in (
                        'QUORUM_REPAIR_REQUIRED', 'EVIDENCE_AUDIT_REPAIR_REQUIRED', 'EVIDENCE_AUDIT_BLOCKED'
                      )
                  )
                order by cell_key
                """, (rs, rowNum) -> new CellScope(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getBoolean(5), rs.getString(6)), runId);
        int created = 0;
        int replayed = 0;
        int enqueued = 0;
        for (List<CellScope> bundle : bundles(cells)) {
            CellScope first = bundle.get(0);
            TaskPriority priority = priorityFor(bundle);
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
                                sourcePolicyForSlot(run, slotSources, candidateQuorum, candidateSlot),
                                withEntityLabel(brief.queryPolicy(), run.id(), entityId), run.researchIntent(), run.controlPack())
                ));
                jdbcTemplate.update("""
                        update research_agent_task
                        set logical_task_key = ?, quorum_group_key = ?, candidate_quorum = ?, candidate_slot = ?,
                            snapshot_schema_version = 'research-agent-task-snapshot.v3', snapshot_digest = null,
                            priority_score = ?, priority_reason = ?
                        where id = ?
                        """, "deep-cell:" + logicalFingerprint, quorumGroupKey, candidateQuorum, candidateSlot,
                        priority.score(), priority.reason(), task.taskId());
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
        ResearchBriefCompiler.CompiledBrief brief = researchBriefCompiler.compile(run.id(), run.question());
        List<Map<String, Object>> sources = loadTrustedSources(
                run.workspaceId(), run.sourceScopeJson(), run.retrievalMode());
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
            if (independentSources.isEmpty() && run.retrievalMode().usesSeeds()) {
                continue;
            }
            String fingerprint = repairFingerprint(run.id(), command.parentCheckpointSeq(), command.waveNo(), cell, target);
            // DR-304: a conflict repair gets its own logical-key prefix so its attempts are
            // independently countable, and its context carries the conflict trace anchor so the
            // counterfactual is scoped to the exact claim, not merely to the cell.
            String logicalPrefix = target.conflictAnchored() ? "conflict-counterfactual:" : "counterfactual:";
            TaskPriority priority = repairPriority(cell);
            Map<String, Object> snapshotBudget = new LinkedHashMap<>(deepCellBudget(1));
            ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                    run.id(), logicalPrefix + fingerprint, "repair:" + fingerprint, command.waveNo(), "COUNTERFACTUAL",
                    entityId(cell.cellKey()), "branch-counterfactual", cell.planRevision(), cell.entitySetVersion(),
                    List.of(cell.cellKey()), snapshotBudget,
                    List.of(new ResearchAgentTaskService.TargetCellBinding(cell.cellKey(), cell.version())),
                    new ResearchAgentTaskService.TaskExecutionContext("research-default",
                            repairSourcePolicy(run, independentSources, target.excludedSourceIds()),
                            withEntityLabel(repairQueryPolicy(brief, target.reasonDigest(), command.parentCheckpointSeq(),
                                    target.conflictTraceId(), target.conflictEvidenceKeys()), run.id(), entityId(cell.cellKey())),
                            run.researchIntent(), run.controlPack())
            ));
            jdbcTemplate.update("""
                    update research_agent_task
                    set logical_task_key = ?, quorum_group_key = null, candidate_quorum = 1, candidate_slot = 1,
                        snapshot_schema_version = 'research-agent-task-snapshot.v3', snapshot_digest = null,
                        priority_score = ?, priority_reason = ?,
                        updated_at = current_timestamp
                    where id = ?
                    """, logicalPrefix + fingerprint, priority.score(), priority.reason(), task.taskId());
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
                select id, workspace_id, coalesce(execution_question, question),
                       source_scope_json, research_intent_json, control_pack_json,
                       retrieval_mode, status, agent_execution_mode
                from research_run where id = ? for update
                """, rs -> rs.next() ? new RunScope(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        readRequiredMap(rs.getString(5), "research intent"),
                        readRequiredMap(rs.getString(6), "control pack"),
                        ResearchRetrievalMode.valueOf(rs.getString(7)), rs.getString(8), rs.getString(9)) : null, runId);
        if (run == null) throw new BusinessException("RESEARCH_AGENT_RUN_NOT_FOUND", "Research run does not exist");
        if (!"INCREMENTAL_V1".equals(run.executionMode())) throw new BusinessException("RESEARCH_AGENT_COORDINATOR_MODE_INVALID", "Task coordinator requires INCREMENTAL_V1");
        if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(run.status())) throw new BusinessException("RESEARCH_AGENT_RUN_TERMINAL", "Cannot taskize terminal run");
        return run;
    }

    private Map<String, Object> withEntityLabel(
            Map<String, Object> basePolicy,
            String runId,
            String entityId
    ) {
        Map<String, Object> policy = new LinkedHashMap<>(basePolicy);
        String label = jdbcTemplate.query("""
                select source_title from research_row
                where research_run_id = ? and row_key = ?
                """, rs -> rs.next() ? rs.getString(1) : null, runId, entityId);
        if (label != null && !label.isBlank() && !"Subject".equalsIgnoreCase(label.strip())) {
            policy.put("entity_label", label.strip());
        }
        return Map.copyOf(policy);
    }

    private Map<String, Object> readRequiredMap(String raw, String label) {
        try {
            Map<String, Object> value = objectMapper.readValue(raw, new TypeReference<>() { });
            if (value == null || value.isEmpty()) throw new IllegalArgumentException(label + " is empty");
            return Map.copyOf(value);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new BusinessException("RESEARCH_AGENT_COORDINATOR_RUN_SCOPE_INVALID",
                    "Run " + label + " is invalid");
        }
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

    /**
     * DR-304 pre-flight: can a counterfactual repair be taskized for this run right now? Reuses the
     * exact trusted-source authority of {@link #planCounterfactualRepairs} but never throws, so a
     * coordinator tick can leave a conflict open instead of failing on an unusable source scope.
     */
    boolean counterfactualRepairFeasible(String runId) {
        try {
            RunScope run = requireIncrementalRunnableRun(runId);
            List<Map<String, Object>> sources = loadTrustedSources(
                    run.workspaceId(), run.sourceScopeJson(), run.retrievalMode());
            return !run.retrievalMode().usesSeeds() || !sources.isEmpty();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private List<Map<String, Object>> loadTrustedSources(
            String workspaceId,
            String rawScope,
            ResearchRetrievalMode retrievalMode
    ) {
        List<String> ids;
        if (rawScope == null || rawScope.isBlank()) {
            throw new BusinessException(
                    "RESEARCH_AGENT_COORDINATOR_SOURCE_SCOPE_INVALID", "Run source scope is missing");
        }
        try {
            ids = objectMapper.readValue(rawScope, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new BusinessException(
                    "RESEARCH_AGENT_COORDINATOR_SOURCE_SCOPE_INVALID", "Run source scope is invalid");
        }
        if (ids == null) {
            throw new BusinessException(
                    "RESEARCH_AGENT_COORDINATOR_SOURCE_SCOPE_INVALID", "Run source scope must be an array");
        }
        Set<String> normalizedIds = new LinkedHashSet<>();
        for (String sourceId : ids) {
            if (sourceId == null || sourceId.isBlank() || !normalizedIds.add(sourceId.trim())) {
                throw new BusinessException(
                        "RESEARCH_AGENT_COORDINATOR_SOURCE_SCOPE_INVALID", "Run source scope contains an invalid or duplicate source");
            }
        }
        if (normalizedIds.isEmpty()) {
            if (retrievalMode.usesSeeds()) {
                throw new BusinessException(
                        "RESEARCH_AGENT_COORDINATOR_SOURCE_SCOPE_REQUIRED",
                        retrievalMode + " requires a non-empty trusted source scope");
            }
            return List.of();
        }
        if (!retrievalMode.usesSeeds()) {
            throw new BusinessException(
                    "RESEARCH_AGENT_COORDINATOR_SOURCE_SCOPE_INVALID",
                    "WEB_ONLY cannot contain trusted seed sources");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String sourceId : normalizedIds) {
            Map<String, Object> source = jdbcTemplate.query("""
                    select s.id, s.title, s.source_type, coalesce(s.summary, '') as summary,
                      ss.id as source_snapshot_id,
                      coalesce((select sw.id from source_chunk sc join source_window sw on sw.source_chunk_id = sc.id
                        where sc.source_id = s.id and sc.source_snapshot_id = ss.id
                        order by sc.chunk_no, sw.window_no limit 1), '') as source_window_id,
                      coalesce((select sw.content from source_chunk sc join source_window sw on sw.source_chunk_id = sc.id
                        where sc.source_id = s.id and sc.source_snapshot_id = ss.id
                        order by sc.chunk_no, sw.window_no limit 1), '') as sample_text
                    from source s
                    join source_snapshot ss on ss.source_id = s.id
                      and ss.version_no = (select max(current_ss.version_no)
                          from source_snapshot current_ss where current_ss.source_id = s.id)
                    where s.id = ? and s.workspace_id = ? and s.status = 'READY'
                    """, rs -> rs.next() ? sourceMap(rs) : null, sourceId, workspaceId);
            if (source == null || String.valueOf(source.get("sample_text")).isBlank()) {
                throw new BusinessException(
                        "RESEARCH_AGENT_COORDINATOR_SOURCE_SCOPE_UNAVAILABLE",
                        "Run source scope contains a missing or unusable ready source");
            }
            result.add(source);
        }
        return result;
    }

    private Map<String, Object> sourceMap(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source_id", rs.getString("id")); value.put("title", rs.getString("title"));
        value.put("source_type", rs.getString("source_type")); value.put("summary", rs.getString("summary"));
        value.put("source_snapshot_id", rs.getString("source_snapshot_id"));
        value.put("source_window_id", rs.getString("source_window_id"));
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

    private TaskPriority priorityFor(List<CellScope> bundle) {
        boolean highRisk = bundle.stream().anyMatch(CellScope::highRisk);
        int reportDependency = bundle.stream()
                .mapToInt(cell -> reportDependencyWeight(columnKey(cell.cellKey())))
                .max()
                .orElse(0);
        int score = (highRisk ? 10_000 : 0) + reportDependency;
        String reason = (highRisk ? "HIGH_RISK+" : "STANDARD+") + reportDependencyReason(reportDependency);
        return new TaskPriority(score, reason);
    }

    private TaskPriority repairPriority(CellScope cell) {
        int reportDependency = reportDependencyWeight(columnKey(cell.cellKey()));
        return new TaskPriority(20_000 + reportDependency, "COUNTERFACTUAL+" + reportDependencyReason(reportDependency));
    }

    private int reportDependencyWeight(String columnKey) {
        return switch (columnKey) {
            case "answer" -> 4_000;
            case "key_evidence", "evidence" -> 3_000;
            case "limitations" -> 2_000;
            case "implications" -> 1_000;
            default -> 500;
        };
    }

    private String reportDependencyReason(int weight) {
        return switch (weight) {
            case 4_000 -> "ANSWER";
            case 3_000 -> "EVIDENCE";
            case 2_000 -> "LIMITATIONS";
            case 1_000 -> "IMPLICATIONS";
            default -> "OTHER";
        };
    }

    private String columnKey(String cellKey) {
        int separator = cellKey.indexOf(':');
        return separator < 0 || separator == cellKey.length() - 1 ? "" : cellKey.substring(separator + 1);
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
    static Map<String, Long> deepCellBudget(int targetCellCount) {
        long cells = Math.max(1, targetCellCount);
        long evidence = cells * MAX_EVIDENCE_CARDS_PER_CELL;
        return Map.of(
                // The worker accounts physical provider attempts, including one retry.
                // Keep the frozen reservation aligned with research.extract max_attempts.
                "llm_calls", 2L,
                // Tool grants from earlier leases are charged at completion time. Reserve
                // the task's three-attempt recovery ceiling so a legal retry can settle.
                "search_calls", 3L,
                "fetch_calls", 3L,
                "read_calls", 3L,
                "extract_calls", 3L,
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
                    + "|reason@" + target.reasonDigest() + target.excludedSourceIds().stream().sorted().map(id -> "|exclude@" + id).reduce("", String::concat)
                    + (target.conflictTraceId() == null ? "" : "|conflict@" + target.conflictTraceId());
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            for (byte item : hash) output.append(String.format("%02x", item));
            return output.substring(0, 32);
        } catch (Exception exception) { throw new IllegalStateException("Cannot create repair task fingerprint", exception); }
    }
    public record CoordinatorReceipt(int createdTaskCount, int idempotentReplayCount, int enqueuedCommandCount, int scopedCellCount) { }
    private record TaskPriority(int score, String reason) { }
    public record CounterfactualRepairCommand(String researchRunId, int parentCheckpointSeq, int waveNo,
                                              List<CounterfactualTarget> targets) { }
    public record CounterfactualTarget(String cellKey, String reasonDigest, List<String> excludedSourceIds,
                                       String conflictTraceId, List<String> conflictEvidenceKeys) {
        public CounterfactualTarget {
            excludedSourceIds = excludedSourceIds == null ? List.of() : excludedSourceIds.stream()
                    .filter(value -> value != null && !value.isBlank()).sorted().toList();
            conflictEvidenceKeys = conflictEvidenceKeys == null ? List.of() : conflictEvidenceKeys.stream()
                    .filter(value -> value != null && !value.isBlank()).distinct().sorted().toList();
            conflictTraceId = (conflictTraceId == null || conflictTraceId.isBlank()) ? null : conflictTraceId;
            if (reasonDigest == null || reasonDigest.isBlank()) {
                throw new BusinessException("RESEARCH_AGENT_REPAIR_INVALID", "Counterfactual repair reason digest is required");
            }
        }

        /** DR-304: failed-wave / quorum repairs are not anchored to a conflict trace. */
        public CounterfactualTarget(String cellKey, String reasonDigest, List<String> excludedSourceIds) {
            this(cellKey, reasonDigest, excludedSourceIds, null, List.of());
        }

        /** True when this repair is a DR-304 conflict repair and must carry its trace anchor. */
        boolean conflictAnchored() {
            return conflictTraceId != null;
        }
    }
    private Map<String, Object> sourcePolicy(RunScope run, List<Map<String, Object>> sources) {
        boolean allowExternal = run.retrievalMode().usesWeb() && externalEvidencePolicy.enabled();
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("retrieval_mode", run.retrievalMode().name());
        policy.put("source_scope", sources);
        policy.put("allow_external_search", allowExternal);
        policy.put("allow_external_fetch", allowExternal);
        List<String> allowedDomains = allowedExternalDomains(run.researchIntent());
        if (!allowedDomains.isEmpty()) policy.put("allowed_external_domains", allowedDomains);
        return Map.copyOf(policy);
    }

    private Map<String, Object> sourcePolicyForSlot(
            RunScope run, List<Map<String, Object>> sources, int candidateQuorum, int candidateSlot
    ) {
        Map<String, Object> policy = new LinkedHashMap<>(sourcePolicy(run, sources));
        List<String> domains = allowedExternalDomains(run.researchIntent());
        if (candidateQuorum > 1 && domains.size() >= candidateQuorum) {
            List<String> selected = new ArrayList<>();
            for (int index = candidateSlot - 1; index < domains.size(); index += candidateQuorum) {
                selected.add(domains.get(index));
            }
            policy.put("allowed_external_domains", List.copyOf(selected));
        }
        return Map.copyOf(policy);
    }

    private List<String> allowedExternalDomains(Map<String, Object> researchIntent) {
        Object raw = researchIntent.get("constraints");
        if (!(raw instanceof List<?> constraints)) return List.of();
        String prefix = "SOURCE_DOMAIN_ALLOWLIST:";
        return constraints.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> value.regionMatches(true, 0, prefix, 0, prefix.length()))
                .flatMap(value -> java.util.Arrays.stream(value.substring(prefix.length()).split(",")))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> value.matches("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}"))
                .distinct().sorted().toList();
    }

    private Map<String, Object> repairSourcePolicy(
            RunScope run,
            List<Map<String, Object>> sources,
            List<String> excludedSourceIds
    ) {
        Map<String, Object> policy = new LinkedHashMap<>(sourcePolicy(run, sources));
        policy.put("excluded_source_ids", excludedSourceIds);
        return Map.copyOf(policy);
    }

    private Map<String, Object> repairQueryPolicy(
            ResearchBriefCompiler.CompiledBrief brief,
            String reasonDigest,
            int parentCheckpointSeq,
            String conflictTraceId,
            List<String> conflictEvidenceKeys
    ) {
        Map<String, Object> policy = new LinkedHashMap<>(brief.queryPolicy());
        policy.put("repair_reason_digest", reasonDigest);
        policy.put("parent_checkpoint_seq", parentCheckpointSeq);
        if (conflictTraceId != null) {
            // DR-304 target anchor: the counterfactual must be able to name the exact claim it is
            // refuting, not just the entity/source/query/column.
            policy.put("conflict_trace_id", conflictTraceId);
            policy.put("conflict_evidence_ids", conflictEvidenceKeys);
        }
        return Map.copyOf(policy);
    }

    private record RunScope(
            String id,
            String workspaceId,
            String question,
            String sourceScopeJson,
            Map<String, Object> researchIntent,
            Map<String, Object> controlPack,
            ResearchRetrievalMode retrievalMode,
            String status,
            String executionMode
    ) { }
    private record CellScope(String cellKey, int version, int planRevision, int entitySetVersion,
                             boolean highRisk, String branchId) { }
}
