package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
    // 同一研究对象的普通单元格合并为一个任务，检索、抓取与阅读只做一次。
    // 抽取仍按单元格逐个进行（Worker 每次只给模型一个字段），避免一个显著字段占满
    // 整次抽取、其余单元格拿不到候选。高风险单元格仍单独成任务并做双路取证。
    private static final int DEFAULT_MAX_CELLS_PER_TASK = 3;
    private int maxCellsPerTask = DEFAULT_MAX_CELLS_PER_TASK;
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

    /** 一个深挖任务最多覆盖的普通单元格数，取值 1 到 6；1 表示每个单元格单独成任务。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMaxCellsPerTask(
            @org.springframework.beans.factory.annotation.Value(
                    "${noteweave.research.agent.max-cells-per-task:" + DEFAULT_MAX_CELLS_PER_TASK + "}") int value) {
        this.maxCellsPerTask = Math.max(1, Math.min(6, value));
    }

    int maxCellsPerTask() {
        return maxCellsPerTask;
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
        int scoped = 0;
        boolean loopEnabled = jdbcTemplate.queryForObject("select count(*) from research_discovery_loop where research_run_id = ?", Integer.class, runId) > 0;
        if (loopEnabled) {
            if ("STOPPED".equals(jdbcTemplate.queryForObject("select phase from research_discovery_loop where research_run_id = ?", String.class, runId)))
                return new CoordinatorReceipt(0, 0, 0, 0);
            jdbcTemplate.update("update research_discovery_loop set stop_reason = null where research_run_id = ?", runId);
            List<Map<String, Object>> attempted = new ArrayList<>();
            jdbcTemplate.query("select plan_revision, entity_set_version, target_bindings_json from research_agent_task where research_run_id = ? and role in ('DEEP_CELL','COUNTERFACTUAL')", rs -> {
                var bindings = new ResearchDiscoveryBudget(jdbcTemplate, objectMapper).read(rs.getString(3));
                for (var binding : bindings) attempted.add(Map.of("key", binding.path("cell_id").asText(),
                        "version", binding.path("expected_version").asInt(), "plan", rs.getInt(1), "entities", rs.getInt(2)));
            }, runId);
            cells = cells.stream().filter(c -> attempted.stream().noneMatch(a -> c.cellKey().equals(a.get("key"))
                    && Integer.valueOf(c.version()).equals(a.get("version")) && Integer.valueOf(c.planRevision()).equals(a.get("plan"))
                    && Integer.valueOf(c.entitySetVersion()).equals(a.get("entities")))).toList();
        }
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
            if (!new ResearchDiscoveryBudget(jdbcTemplate, objectMapper).allows(runId,
                    first.highRisk() ? counterCellBudget() : deepCellBudget(bundle.size()), candidateQuorum, "DEEP_CELL")) {
                jdbcTemplate.update("update research_discovery_loop set stop_reason = 'LOOP_BUDGET_EXHAUSTED' where research_run_id = ?", runId);
                continue;
            }
            scoped += bundle.size();
            String quorumGroupKey = first.highRisk() ? "quorum:" + logicalFingerprint : null;
            for (int candidateSlot = 1; candidateSlot <= candidateQuorum; candidateSlot++) {
                String slotFingerprint = candidateQuorum == 1
                        ? logicalFingerprint : logicalFingerprint + ":slot:" + candidateSlot;
                String role = candidateSlot == 1 ? "DEEP_CELL" : "COUNTERFACTUAL";
                List<Map<String, Object>> slotSources = sourcesForSlot(sources, candidateQuorum, candidateSlot);
                Map<String, Long> reservation = first.highRisk() ? counterCellBudget() : deepCellBudget(bundle.size());
                Map<String, Object> snapshotBudget = new LinkedHashMap<>(reservation);
                ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                        run.id(), "deep-cell:" + slotFingerprint, "coordinator:" + slotFingerprint, waveNo, role, entityId,
                        first.branchId() == null ? "branch-main" : first.branchId(),
                        first.planRevision(), first.entitySetVersion(),
                        bundle.stream().map(CellScope::cellKey).toList(), snapshotBudget,
                        bundle.stream().map(cell -> new ResearchAgentTaskService.TargetCellBinding(cell.cellKey(), cell.version())).toList(),
                        new ResearchAgentTaskService.TaskExecutionContext("research-default",
                                sourcePolicyForSlot(run, slotSources, candidateQuorum, candidateSlot),
                                withCellScope(brief.queryPolicy(), run.id(), entityId,
                                        bundle.stream().map(CellScope::cellKey).toList()),
                                run.researchIntent(), run.controlPack())
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
        return new CoordinatorReceipt(created, replayed, enqueued, scoped);
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
        // 同一研究对象且修订范围相同的普通缺口可共用检索和阅读，抽取仍逐列进行。
        // 冲突补查和高风险单元格需要针对单条结论的反证，仍按单元格分别建任务。
        Map<RepairGroupScope, List<RepairCell>> mergeable = new LinkedHashMap<>();
        for (CounterfactualTarget target : targets) {
            if (target.conflictAnchored() || "EVIDENCE_CONFLICT".equals(target.repairCause())) continue;
            CellScope cell = lockRepairableCell(run.id(), target.cellKey());
            if (cell.highRisk() || "EVIDENCE_CONFLICT".equals(repairCause(run.id(), cell.cellKey(), target))) continue;
            // 每个任务只有一组修订号；混合修订会在完成提交时被快照校验拒绝，重试也无法恢复。
            var scope = new RepairGroupScope(entityId(cell.cellKey()), cell.planRevision(), cell.entitySetVersion());
            mergeable.computeIfAbsent(scope, key -> new ArrayList<>()).add(new RepairCell(cell, target));
        }
        Set<String> mergedCells = new LinkedHashSet<>();
        for (List<RepairCell> group : mergeable.values()) {
            List<CellScope> eligibleCells = group.stream().map(RepairCell::cell).toList();
            List<CounterfactualTarget> eligibleTargets = group.stream().map(RepairCell::target).toList();
            // 提交时单个补查任务最多绑定 MAX_MERGED_REPAIR_CELLS 个单元格，超过时均分成几个任务，
            // 否则 4 列的冲突类问题会建出 4 格任务，每次提交都被判为快照失效
            for (int[] range : mergedRepairChunks(eligibleCells.size())) {
                List<CellScope> cells = eligibleCells.subList(range[0], range[1]);
                if (cells.size() < 2) continue;
                MergedRepairReceipt merged = planMergedRepair(run, brief, sources, command,
                        cells, eligibleTargets.subList(range[0], range[1]));
                if (merged == null) continue;
                cells.forEach(cell -> mergedCells.add(cell.cellKey()));
                if (merged.existed()) replayed++; else created++;
                if (merged.enqueued()) enqueued++;
            }
        }
        for (CounterfactualTarget target : targets) {
            if (mergedCells.contains(target.cellKey())) continue;
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
            TaskPriority priority = repairPriority(run.id(), cell);
            boolean requiresCounter = cell.highRisk() || "EVIDENCE_CONFLICT".equals(repairCause(run.id(), cell.cellKey(), target));
            Map<String, Long> repairBudget = requiresCounter ? counterCellBudget() : deepCellBudget(1);
            if (!new ResearchDiscoveryBudget(jdbcTemplate, objectMapper).allows(run.id(), repairBudget, 1, "COUNTERFACTUAL")) {
                jdbcTemplate.update("update research_discovery_loop set phase = 'STOPPED', stop_reason = 'LOOP_BUDGET_EXHAUSTED' where research_run_id = ?", run.id());
                continue;
            }
            Map<String, Object> snapshotBudget = new LinkedHashMap<>(repairBudget);
            ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                    run.id(), logicalPrefix + fingerprint, "repair:" + fingerprint, command.waveNo(), "COUNTERFACTUAL",
                    entityId(cell.cellKey()), "branch-counterfactual", cell.planRevision(), cell.entitySetVersion(),
                    List.of(cell.cellKey()), snapshotBudget,
                    List.of(new ResearchAgentTaskService.TargetCellBinding(cell.cellKey(), cell.version())),
                    new ResearchAgentTaskService.TaskExecutionContext("research-default",
                            repairSourcePolicy(run, independentSources, target.excludedSourceIds()),
                            withRepairTarget(run.id(), cell.cellKey(), target, withCellScope(repairQueryPolicy(brief, target.reasonDigest(), command.parentCheckpointSeq(),
                                    target.conflictTraceId(), target.conflictEvidenceKeys()), run.id(), entityId(cell.cellKey()),
                                    List.of(cell.cellKey()))),
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
                    run.id(), task.taskId(), "repair-reserve:" + fingerprint, repairBudget));
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

    private record MergedRepairReceipt(boolean existed, boolean enqueued) { }

    /** 与提交校验的单任务单元格上限一致。 */
    static final int MAX_MERGED_REPAIR_CELLS = 3;

    /** 把 n 个单元格尽量均匀地分成每组不超过上限的区间，例如 4 个分成 2+2，7 个分成 3+2+2。 */
    static List<int[]> mergedRepairChunks(int size) {
        if (size <= 0) return List.of();
        int chunks = (size + MAX_MERGED_REPAIR_CELLS - 1) / MAX_MERGED_REPAIR_CELLS;
        List<int[]> result = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < chunks; index++) {
            int length = size / chunks + (index < size % chunks ? 1 : 0);
            result.add(new int[] {start, start + length});
            start += length;
        }
        return result;
    }

    /** 为同一研究对象的多个缺口单元格建一个补查任务；预算不足或没有可用来源时返回 null，交给逐个单元格的路径处理。 */
    private MergedRepairReceipt planMergedRepair(
            RunScope run, ResearchBriefCompiler.CompiledBrief brief, List<Map<String, Object>> sources,
            CounterfactualRepairCommand command, List<CellScope> cells, List<CounterfactualTarget> targets
    ) {
        List<String> excluded = targets.stream().flatMap(target -> target.excludedSourceIds().stream())
                .distinct().sorted().toList();
        List<Map<String, Object>> independentSources = sources.stream()
                .filter(source -> !excluded.contains(String.valueOf(source.get("source_id"))))
                .toList();
        if (independentSources.isEmpty() && run.retrievalMode().usesSeeds()) return null;
        Map<String, Long> repairBudget = deepCellBudget(cells.size());
        if (!new ResearchDiscoveryBudget(jdbcTemplate, objectMapper).allows(run.id(), repairBudget, 1, "COUNTERFACTUAL")) return null;
        String fingerprint;
        try {
            StringBuilder text = new StringBuilder("merged-repair");
            for (int index = 0; index < cells.size(); index++) {
                text.append('|').append(repairFingerprint(run.id(), command.parentCheckpointSeq(), command.waveNo(),
                        cells.get(index), targets.get(index)));
            }
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            for (byte item : hash) output.append(String.format("%02x", item));
            fingerprint = output.substring(0, 32);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create merged repair fingerprint", exception);
        }
        CellScope first = cells.get(0);
        List<String> cellKeys = cells.stream().map(CellScope::cellKey).toList();
        String reasonDigest = targets.get(0).reasonDigest();
        Map<String, Object> queryPolicy = withRepairTarget(run.id(), first.cellKey(), targets.get(0),
                withCellScope(repairQueryPolicy(brief, reasonDigest, command.parentCheckpointSeq(), null, List.of()),
                        run.id(), entityId(first.cellKey()), cellKeys));
        TaskPriority priority = cells.stream().map(cell -> repairPriority(run.id(), cell))
                .max(java.util.Comparator.comparingInt(TaskPriority::score)).orElse(repairPriority(run.id(), first));
        String logicalKey = "counterfactual:" + fingerprint;
        ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                run.id(), logicalKey, "repair:" + fingerprint, command.waveNo(), "COUNTERFACTUAL",
                entityId(first.cellKey()), "branch-counterfactual", first.planRevision(), first.entitySetVersion(),
                cellKeys, new LinkedHashMap<>(repairBudget),
                cells.stream().map(cell -> new ResearchAgentTaskService.TargetCellBinding(cell.cellKey(), cell.version())).toList(),
                new ResearchAgentTaskService.TaskExecutionContext("research-default",
                        repairSourcePolicy(run, independentSources, excluded), queryPolicy,
                        run.researchIntent(), run.controlPack())));
        jdbcTemplate.update("""
                update research_agent_task
                set logical_task_key = ?, quorum_group_key = null, candidate_quorum = 1, candidate_slot = 1,
                    snapshot_schema_version = 'research-agent-task-snapshot.v3', snapshot_digest = null,
                    priority_score = ?, priority_reason = ?,
                    updated_at = current_timestamp
                where id = ?
                """, logicalKey, priority.score(), priority.reason(), task.taskId());
        boolean existed = jdbcTemplate.queryForObject("select count(*) from research_budget_reservation where research_agent_task_id = ?", Integer.class, task.taskId()) > 0;
        budgetService.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                run.id(), task.taskId(), "repair-reserve:" + fingerprint, repairBudget));
        ResearchAgentCommandOutboxService.CommandReceipt outbox = outboxService.enqueue(task.taskId());
        if (!existed) {
            for (CellScope cell : cells) {
                int updated = jdbcTemplate.update("""
                        update research_cell set repair_count = repair_count + 1, updated_at = current_timestamp
                        where research_run_id = ? and cell_key = ? and cell_version = ? and active_task_id is null
                        """, run.id(), cell.cellKey(), cell.version());
                if (updated != 1) throw new BusinessException("RESEARCH_AGENT_REPAIR_TARGET_INVALID", "Counterfactual target changed while taskizing");
            }
        }
        return new MergedRepairReceipt(existed, !outbox.idempotentReplay());
    }

    /** 宽搜索读取整个当前表格，任务仅能提交范围提案。 */
    @Transactional
    public CoordinatorReceipt planDiscoveryTask(String runId, int waveNo, int roundNo) {
        RunScope run = requireIncrementalRunnableRun(runId);
        List<Map<String, Object>> sources = loadTrustedSources(run.workspaceId(), run.sourceScopeJson(), run.retrievalMode());
        List<Map<String, Object>> rows = jdbcTemplate.query("select row_key, source_title from research_row where research_run_id = ? order by row_key",
                (rs, n) -> Map.of("key", rs.getString(1), "label", rs.getString(2) == null ? "" : rs.getString(2)), runId);
        List<Map<String, Object>> columns = new ArrayList<>(columnDefinitions(runId).values());
        columnLabels(runId).forEach((key, label) -> {
            if (columns.stream().noneMatch(c -> key.equals(c.get("key")))) columns.add(Map.of("key", key, "label", label));
        });
        List<Map<String, Object>> verified = jdbcTemplate.query("select cell_key, candidate_value from research_formal_eligible_cell where research_run_id = ? order by cell_key",
                (rs, n) -> Map.of("cell_key", rs.getString(1), "value", discoverySummary(rs.getString(2))), runId);
        int plan = jdbcTemplate.queryForObject("select coalesce(max(plan_revision), 1) from research_cell where research_run_id = ?", Integer.class, runId);
        int entities = jdbcTemplate.queryForObject("select coalesce(max(entity_set_version), 1) from research_cell where research_run_id = ?", Integer.class, runId);
        List<Map<String, Object>> prior = jdbcTemplate.query("select proposal_type, label from research_discovery_proposal where research_run_id = ? order by created_at, id",
                (rs, n) -> Map.of("proposal_type", rs.getString(1), "label", rs.getString(2)), runId);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schema_version", "research-discovery-input.v2");
        input.put("question", run.question());
        input.put("table", Map.of("entities", rows, "columns", columns, "verified_cells", verified));
        input.put("prior_proposals", prior);
        List<String> previousQueries = new ArrayList<>();
        for (String json : jdbcTemplate.queryForList("select queries_json from research_discovery_round where research_run_id = ? and queries_json is not null order by round_no", String.class, runId))
            previousQueries.addAll(readStringListOrEmpty(json));
        input.put("previous_queries", previousQueries.stream().distinct().toList());
        input.put("authoritative_domains", planGuidance(runId).getOrDefault("authoritative_domains", List.of()));
        input.put("plan_revision", plan); input.put("entity_set_version", entities);
        input.put("round_no", roundNo); input.put("query_limit", 3); input.put("max_cells", 80);
        input.put("allowed_source_ids", sources.stream().map(s -> s.get("source_id")).toList());
        var canonicalizer = new ResearchAgentCompletionCanonicalizer();
        input.put("input_digest", canonicalizer.domainSeparatedDigest("research-discovery-input.v2", input));
        String logicalKey = "wide-discovery:" + runId + ":" + roundNo;
        Map<String, Object> discoverySourcePolicy = new LinkedHashMap<>(sourcePolicy(run, sources));
        discoverySourcePolicy.put("allow_external_fetch", false);
        Map<String, Long> budget = new LinkedHashMap<>(deepCellBudget(1));
        budget.replaceAll((key, value) -> 0L);
        budget.put("llm_calls", 4L); budget.put("search_calls", 9L);
        ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                run.id(), logicalKey, logicalKey, waveNo, "WIDE_DISCOVERY", "scope-proposal", "branch-main", plan, entities,
                List.of(), new LinkedHashMap<String, Object>(budget), List.of(),
                new ResearchAgentTaskService.TaskExecutionContext("research-default", discoverySourcePolicy,
                        Map.of("allowed_tools", List.of("search", "read"), "discovery_input", input), run.researchIntent(), run.controlPack())));
        jdbcTemplate.update("update research_agent_task set logical_task_key = ?, candidate_quorum = 1, candidate_slot = 1, snapshot_schema_version = 'research-agent-task-snapshot.v3', snapshot_digest = null where id = ?", logicalKey, task.taskId());
        boolean existed = jdbcTemplate.queryForObject("select count(*) from research_budget_reservation where research_agent_task_id = ?", Integer.class, task.taskId()) > 0;
        budgetService.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(runId, task.taskId(), "reserve:" + logicalKey, budget));
        var command = outboxService.enqueue(task.taskId());
        if (jdbcTemplate.queryForObject("select count(*) from research_discovery_round where task_id = ?", Integer.class, task.taskId()) == 0)
            jdbcTemplate.update("insert into research_discovery_round(id, research_run_id, round_no, task_id, round_status) values (?, ?, ?, ?, 'PENDING')", com.noteweave.common.Ids.newId(), runId, roundNo, task.taskId());
        return new CoordinatorReceipt(existed ? 0 : 1, existed ? 1 : 0, command.idempotentReplay() ? 0 : 1, 0);
    }

    private String discoverySummary(String value) {
        if (value == null) return "";
        return value.codePointCount(0, value.length()) <= 1000 ? value : value.substring(0, value.offsetByCodePoints(0, 1000));
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

    /**
     * 给任务的查询策略补上实体名和目标列的标签。Worker 按列的含义生成检索词，
     * 只拿到 finding-xxxx 这样的列键时无法知道该检索什么。
     */
    private Map<String, Object> withCellScope(
            Map<String, Object> basePolicy,
            String runId,
            String entityId,
            List<String> cellKeys
    ) {
        Map<String, Object> policy = new LinkedHashMap<>(basePolicy);
        String label = jdbcTemplate.query("""
                select source_title from research_row
                where research_run_id = ? and row_key = ?
                """, rs -> rs.next() ? rs.getString(1) : null, runId, entityId);
        if (label != null && !label.isBlank() && !"Subject".equalsIgnoreCase(label.strip())) {
            policy.put("entity_label", label.strip());
        }
        Map<String, String> columnLabels = columnLabels(runId);
        Map<String, Map<String, Object>> columnDefinitions = columnDefinitions(runId);
        List<Map<String, Object>> columnSpecs = new ArrayList<>();
        Set<String> seenColumns = new LinkedHashSet<>();
        for (String cellKey : cellKeys) {
            int separator = cellKey.indexOf(':');
            String columnKey = separator < 0 ? cellKey : cellKey.substring(separator + 1);
            if (!seenColumns.add(columnKey)) continue;
            Map<String, Object> definition = columnDefinitions.get(columnKey);
            columnSpecs.add(definition != null ? definition
                    : Map.of("key", columnKey, "label", columnLabels.getOrDefault(columnKey, columnKey)));
        }
        policy.put("column_specs", List.copyOf(columnSpecs));
        List<String> tried = new ArrayList<>();
        for (String cellKey : cellKeys) {
            tried.addAll(jdbcTemplate.queryForList("select query_text from research_task_query where research_run_id = ? and cell_key = ? order by created_at, id",
                    String.class, runId, cellKey));
        }
        policy.put("tried_queries", tried.stream().distinct().toList());
        Boolean counterRequired = jdbcTemplate.queryForObject("select count(*) > 0 from research_cell where research_run_id = ? and high_risk = true and cell_key in ("
                + String.join(",", java.util.Collections.nCopies(cellKeys.size(), "?")) + ")", Boolean.class,
                java.util.stream.Stream.concat(java.util.stream.Stream.of(runId), cellKeys.stream()).toArray());
        if (Boolean.TRUE.equals(counterRequired)) policy.put("counter_required", true);
        policy.putAll(planGuidance(runId));
        return policy;
    }

    /** 模型规划写入的列定义；没有经过模型规划的 Run 返回空表，退回使用列标签。 */
    private Map<String, Map<String, Object>> columnDefinitions(String runId) {
        Map<String, Map<String, Object>> definitions = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select column_key, label, description, answer_format, search_intents_json
                from research_column where research_run_id = ? order by created_at, column_key
                """, rs -> {
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("key", rs.getString(1));
            definition.put("label", rs.getString(2));
            definition.put("description", rs.getString(3) == null ? "" : rs.getString(3));
            definition.put("answer_format", rs.getString(4));
            definition.put("search_intents", readStringListOrEmpty(rs.getString(5)));
            definitions.put(rs.getString(1), Map.copyOf(definition));
        }, runId);
        return definitions;
    }

    /** 模型规划给出的研究目标、投入等级、排除项和权威域名，供 Worker 生成检索词时参考，不限制检索范围。 */
    private Map<String, Object> planGuidance(String runId) {
        List<String> plans = jdbcTemplate.queryForList("""
                select plan_json from research_matrix_plan
                where research_run_id = ? and plan_mode = 'LLM_MATRIX'
                order by created_at desc, id desc limit 1
                """, String.class, runId);
        if (plans.isEmpty()) return Map.of();
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(plans.get(0));
            if (node.isTextual()) node = objectMapper.readTree(node.asText());
            Map<String, Object> guidance = new LinkedHashMap<>();
            String objective = node.path("brief").path("objective").asText("");
            if (!objective.isBlank()) guidance.put("objective", objective);
            String effortTier = node.path("effort_tier").asText("");
            if (!effortTier.isBlank()) guidance.put("effort_tier", effortTier);
            List<String> domains = new ArrayList<>();
            node.path("authoritative_domains").forEach(item -> domains.add(item.asText()));
            if (!domains.isEmpty()) guidance.put("authoritative_domains", List.copyOf(domains));
            List<String> exclusions = new ArrayList<>();
            node.path("brief").path("exclusions").forEach(item -> exclusions.add(item.asText()));
            if (!exclusions.isEmpty()) guidance.put("boundaries", List.copyOf(exclusions));
            Map<String, List<String>> requirements = new LinkedHashMap<>();
            node.path("columns").forEach(column -> {
                List<String> points = new ArrayList<>();
                column.path("requirements").forEach(point -> { if (point.isTextual() && !point.asText().isBlank()) points.add(point.asText()); });
                String key = column.path("key").asText("");
                if (!key.isBlank() && !points.isEmpty()) requirements.put(key, List.copyOf(points));
            });
            if (!requirements.isEmpty()) guidance.put("column_requirements", Map.copyOf(requirements));
            return Map.copyOf(guidance);
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private List<String> readStringListOrEmpty(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(raw);
            if (node.isTextual()) node = objectMapper.readTree(node.asText());
            List<String> values = new ArrayList<>();
            node.forEach(item -> values.add(item.asText()));
            return List.copyOf(values);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    /**
     * 为规划阶段下发唯一的 PLANNING 任务。任务键按 Run 固定，重复调用只会重放已有任务；
     * 检索预算只够一次探测检索，模型调用预算为生成一次，加一次修复或覆盖复查。
     */
    @Transactional
    public CoordinatorReceipt planPlanningTask(String runId, Map<String, Object> planningInput) {
        RunScope run = requireIncrementalRunnableRun(runId);
        ResearchBriefCompiler.CompiledBrief brief = researchBriefCompiler.compile(run.id(), run.question());
        List<Map<String, Object>> sources = loadTrustedSources(
                run.workspaceId(), run.sourceScopeJson(), run.retrievalMode());
        String logicalKey = "planning:" + run.id();
        Map<String, Long> budget = planningBudget();
        Map<String, Object> queryPolicy = new LinkedHashMap<>(brief.queryPolicy());
        queryPolicy.put("allowed_tools", List.of("search"));
        queryPolicy.put("planning_input", planningInput);
        ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                run.id(), logicalKey, logicalKey, 1, ResearchAgentRoles.PLANNING, "planning", "branch-main",
                1, 1, List.of(), new LinkedHashMap<String, Object>(budget), List.of(),
                new ResearchAgentTaskService.TaskExecutionContext("research-default",
                        sourcePolicy(run, sources), Map.copyOf(queryPolicy), run.researchIntent(), run.controlPack())));
        jdbcTemplate.update("""
                update research_agent_task
                set logical_task_key = ?, candidate_quorum = 1, candidate_slot = 1,
                    snapshot_schema_version = 'research-agent-task-snapshot.v3', snapshot_digest = null
                where id = ?
                """, logicalKey, task.taskId());
        boolean existed = jdbcTemplate.queryForObject(
                "select count(*) from research_budget_reservation where research_agent_task_id = ?",
                Integer.class, task.taskId()) > 0;
        budgetService.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                run.id(), task.taskId(), "reserve:" + logicalKey, budget));
        ResearchAgentCommandOutboxService.CommandReceipt outbox = outboxService.enqueue(task.taskId());
        return new CoordinatorReceipt(existed ? 0 : 1, existed ? 1 : 0, outbox.idempotentReplay() ? 0 : 1, 0);
    }

    /** PLANNING 任务的预算：一次探测检索，模型生成一次、复核或修复一次，前两次都不合格时再修复一次。 */
    static Map<String, Long> planningBudget() {
        Map<String, Long> budget = new LinkedHashMap<>();
        budget.put("llm_calls", 3L);
        budget.put("search_calls", 1L);
        budget.put("fetch_calls", 0L);
        budget.put("read_calls", 0L);
        budget.put("extract_calls", 0L);
        budget.put("evidence_cards", 0L);
        budget.put("evidence_appended", 0L);
        budget.put("candidates_submitted", 0L);
        budget.put("candidate_merges_accepted", 0L);
        budget.put("candidate_merges_rejected", 0L);
        return Map.copyOf(budget);
    }

    /** 列标签来自初始表格规划（含断点恢复时复制的规划）和已接受的维度提案，后写入的覆盖先写入的。 */
    private Map<String, String> columnLabels(String runId) {
        Map<String, String> labels = new LinkedHashMap<>();
        List<String> plans = jdbcTemplate.queryForList("""
                select plan_json from research_matrix_plan
                where research_run_id = ? and plan_mode = 'INTENT_MATRIX_V2'
                order by created_at, id
                """, String.class, runId);
        for (String planJson : plans) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(planJson);
                if (node.isTextual()) node = objectMapper.readTree(node.asText());
                for (com.fasterxml.jackson.databind.JsonNode column : node.path("columns")) {
                    String key = column.path("key").asText("");
                    String columnLabel = column.path("label").asText("");
                    if (!key.isBlank() && !columnLabel.isBlank()) labels.put(key, columnLabel.strip());
                }
            } catch (JsonProcessingException exception) {
                // 规划 JSON 损坏时不影响任务下发，Worker 会退回使用列键
            }
        }
        jdbcTemplate.query("""
                select candidate_key, label from research_discovery_proposal
                where research_run_id = ? and proposal_type = 'DIMENSION' and proposal_status = 'ACCEPTED'
                order by created_at, id
                """, rs -> {
            String key = rs.getString(1);
            String columnLabel = rs.getString(2);
            if (key != null && columnLabel != null && !columnLabel.isBlank()) labels.put(key, columnLabel.strip());
        }, runId);
        return labels;
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
                where research_run_id = ? and cell_key = ? and cell_status <> 'FROZEN' and active_task_id is null
                  and (cell_status <> 'VERIFIED' or semantic_audit_status in ('BLOCKED','NOT_RUN','CONFLICT'))
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
        for (int i = 0; i < cells.size(); i += maxCellsPerTask) {
            result.add(List.copyOf(cells.subList(i, Math.min(cells.size(), i + maxCellsPerTask))));
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

    private TaskPriority repairPriority(String runId, CellScope cell) {
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
                // Worker 按调用次数结算：检索词规划最多 2 次（生成一次，输出不合规时修复一次）；
                // 每个单元格单独抽取最多 2 次，最多 6 张卡产生 15 对等价判定，每对最多 2 次。
                "llm_calls", 2L + cells * 32L,
                // Tool grants from earlier leases are charged at completion time. Reserve
                // the task's three-attempt recovery ceiling so a legal retry can settle.
                // Worker 在有要求没答到时补查一轮，每次尝试最多用两轮工具，三次尝试共六轮
                "search_calls", 6L,
                "fetch_calls", 6L,
                "read_calls", 6L,
                "extract_calls", 6L,
                "evidence_cards", evidence,
                "evidence_appended", evidence,
                "candidates_submitted", cells,
                "candidate_merges_accepted", cells,
                "candidate_merges_rejected", cells);
    }
    /** 在隔离反证预算上增加最多 30 次等价判定调用；工具包含三次租约恢复上限。 */
    static Map<String, Long> counterCellBudget() {
        Map<String, Long> budget = new LinkedHashMap<>(deepCellBudget(1));
        budget.put("llm_calls", 64L);
        budget.put("search_calls", 21L);
        budget.put("fetch_calls", 21L);
        budget.put("read_calls", 21L);
        budget.put("evidence_cards", 12L);
        budget.put("evidence_appended", 12L);
        return Map.copyOf(budget);
    }

    private Map<String, Object> withRepairTarget(String runId, String cellKey, CounterfactualTarget target,
                                                Map<String, Object> scoped) {
        Map<String, Object> policy = new LinkedHashMap<>(scoped);
        Map<String, Object> repair = new LinkedHashMap<>();
        repair.put("cell_key", cellKey);
        repair.put("column_specs", scoped.get("column_specs"));
        String cause = repairCause(runId, cellKey, target);
        repair.put("repair_cause", cause);
        repair.put("tried_queries", scoped.get("tried_queries"));
        List<Map<String, Object>> claims = new ArrayList<>();
        for (String key : target.conflictEvidenceKeys()) {
            claims.addAll(jdbcTemplate.query("select claim_text, coalesce(source_domain, '') as source_domain from source_evidence where research_run_id = ? and evidence_key = ?",
                    (rs, n) -> Map.<String, Object>of("claim_text", rs.getString(1), "source_domain", rs.getString(2)), runId, key));
        }
        if (claims.isEmpty() && "EVIDENCE_CONFLICT".equals(cause)) {
            List<String> candidateEvidence = jdbcTemplate.queryForList("select evidence_ids_json from research_agent_candidate where research_run_id = ? and cell_key = ? order by submitted_at desc, id desc limit 2",
                    String.class, runId, cellKey);
            for (String json : candidateEvidence) {
                try {
                    var keys = objectMapper.readTree(json);
                    if (keys.isTextual()) keys = objectMapper.readTree(keys.asText());
                    for (var key : keys) claims.addAll(jdbcTemplate.query(
                            "select claim_text, coalesce(source_domain, '') from source_evidence where research_run_id = ? and evidence_key = ?",
                            (rs, n) -> Map.<String, Object>of("claim_text", rs.getString(1), "source_domain", rs.getString(2)), runId, key.asText()));
                } catch (JsonProcessingException exception) {
                    throw new BusinessException("RESEARCH_AGENT_REPAIR_INVALID", "修复候选的证据列表不符合约定");
                }
            }
        }
        repair.put("claims", claims.stream().distinct().toList());
        policy.put("repair_target", repair);
        Integer equivalentRepair = jdbcTemplate.queryForObject("select count(*) from research_verifier_decision where research_run_id = ? and target_id = ? and decision_type = 'QUORUM_REPAIR_REQUIRED' and reason_code = 'SEMANTIC_EQUIVALENCE_APPROVED' and decision_status = 'OPEN'",
                Integer.class, runId, cellKey);
        if (equivalentRepair != null && equivalentRepair > 0 && target.excludedSourceIds().isEmpty()) policy.put("equivalence_repair", true);
        if ("EVIDENCE_CONFLICT".equals(cause)) policy.put("counter_required", true);
        return Map.copyOf(policy);
    }

    private String repairCause(String runId, String cellKey, CounterfactualTarget target) {
        if (target.conflictAnchored()) return "EVIDENCE_CONFLICT";
        String cause = target.repairCause() == null ? repairCauseForCell(runId, cellKey) : target.repairCause();
        return Set.of("EVIDENCE_CONFLICT", "FETCH_FAILED", "PROVIDER_FAILED").contains(cause)
                ? cause : "MARGINAL_GAIN_EXHAUSTED";
    }

    private String repairCauseForCell(String runId, String cellKey) {
        List<String> reasons = jdbcTemplate.queryForList("select reason_code from research_verifier_decision where research_run_id = ? and target_id = ? order by created_at desc, id desc limit 1", String.class, runId, cellKey);
        String reason = reasons.isEmpty() ? "" : reasons.get(0);
        return java.util.Set.of("FETCH_FAILED", "PROVIDER_FAILED", "EVIDENCE_CONFLICT").contains(reason == null ? "" : reason)
                ? reason : "MARGINAL_GAIN_EXHAUSTED";
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
                                       String conflictTraceId, List<String> conflictEvidenceKeys, String repairCause) {
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
            this(cellKey, reasonDigest, excludedSourceIds, null, List.of(), null);
        }

        public CounterfactualTarget(String cellKey, String reasonDigest, List<String> excludedSourceIds,
                                    String conflictTraceId, List<String> conflictEvidenceKeys) {
            this(cellKey, reasonDigest, excludedSourceIds, conflictTraceId, conflictEvidenceKeys, null);
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
    private record RepairGroupScope(String entityId, int planRevision, int entitySetVersion) { }
    private record RepairCell(CellScope cell, CounterfactualTarget target) { }
}
