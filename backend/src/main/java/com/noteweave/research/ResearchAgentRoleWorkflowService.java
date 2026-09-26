package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Feature-gated Audit -> Synthesis orchestration after the canonical cell barrier settles. */
@Service
class ResearchAgentRoleWorkflowService {
    private static final String AUDIT_DIGEST_DOMAIN = "research-evidence-audit-input.v1";
    private static final String LEDGER_DIGEST_DOMAIN = "research-synthesis-ledger.v1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentTaskService taskService;
    private final ResearchBudgetAndCheckpointService budgetService;
    private final ResearchAgentCommandOutboxService outboxService;
    private final ResearchAgentCompletionCanonicalizer canonicalizer;
    private final boolean auditEnabled;
    private final boolean synthesisEnabled;
    private final boolean wideDiscoveryEnabled;
    private final ResearchAgentFeatureFlagService featureFlags;

    ResearchAgentRoleWorkflowService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentTaskService taskService,
            ResearchBudgetAndCheckpointService budgetService,
            ResearchAgentCommandOutboxService outboxService,
            ResearchAgentCompletionCanonicalizer canonicalizer,
            ResearchAgentFeatureFlagService featureFlags,
            @Value("${noteweave.research.evidence-audit-v1:false}") boolean auditEnabled,
            @Value("${noteweave.research.worker-synthesis-v1:false}") boolean synthesisEnabled,
            @Value("${noteweave.research.wide-discovery-v1:false}") boolean wideDiscoveryEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.taskService = taskService;
        this.budgetService = budgetService;
        this.outboxService = outboxService;
        this.canonicalizer = canonicalizer;
        this.featureFlags = featureFlags;
        this.auditEnabled = auditEnabled;
        this.synthesisEnabled = synthesisEnabled;
        this.wideDiscoveryEnabled = wideDiscoveryEnabled;
    }

    WorkflowReceipt advance(String runId, int waveNo) {
        if (wideDiscoveryEnabled && featureFlags.enabledForRun(
                runId, ResearchAgentFeatureFlagService.WIDE_DISCOVERY)) {
            RoleResult discovery = latestRoleResult(runId, "WIDE_DISCOVERY");
            if (discovery == null) {
                if (activeRoleTask(runId, "WIDE_DISCOVERY")) return new WorkflowReceipt("DISCOVERY_PENDING", null);
                return new WorkflowReceipt("DISCOVERY_TASKIZED", taskizeDiscovery(runId, waveNo + 1));
            }
        }
        if (!auditEnabled || !featureFlags.enabledForRun(
                runId, ResearchAgentFeatureFlagService.EVIDENCE_AUDIT)) {
            return new WorkflowReceipt("FALLBACK_READY", null);
        }
        RoleResult audit = latestRoleResult(runId, "EVIDENCE_AUDIT");
        if (audit == null) {
            if (activeRoleTask(runId, "EVIDENCE_AUDIT")) return new WorkflowReceipt("AUDIT_PENDING", null);
            return new WorkflowReceipt("AUDIT_TASKIZED", taskizeAudit(runId, waveNo + 1));
        }
        if (!"PASS".equals(audit.status())) return new WorkflowReceipt("AUDIT_BLOCKED", null);
        if (!synthesisEnabled || !featureFlags.enabledForRun(
                runId, ResearchAgentFeatureFlagService.WORKER_SYNTHESIS)) {
            return new WorkflowReceipt("FALLBACK_READY", null);
        }
        Integer validated = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_synthesis_candidate
                where research_run_id = ? and status = 'VALIDATED'
                """, Integer.class, runId);
        if (validated != null && validated > 0) return new WorkflowReceipt("SYNTHESIS_READY", null);
        if (activeRoleTask(runId, "SYNTHESIS")) return new WorkflowReceipt("SYNTHESIS_PENDING", null);
        return new WorkflowReceipt("SYNTHESIS_TASKIZED", taskizeSynthesis(runId, waveNo + 2, audit));
    }

    private ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskizeAudit(String runId, int waveNo) {
        RunScope run = runScope(runId);
        List<CellScope> cells = verifiedCells(runId);
        List<Map<String, Object>> auditCells = new ArrayList<>();
        for (CellScope cell : cells) {
            List<Map<String, Object>> evidence = jdbcTemplate.query("""
                    select evidence.evidence_key, evidence.source_domain, evidence.lineage_digest,
                           coalesce(validation.final_status, 'QUALIFIED') as final_status,
                           coalesce(validation.deterministic_status, 'PASS') as typed_status
                    from research_cell_evidence binding
                    join source_evidence evidence on evidence.id = binding.source_evidence_id
                    left join research_evidence_validation validation on validation.evidence_id = evidence.id
                    where binding.research_cell_id = ? order by evidence.evidence_key
                    """, (rs, rowNum) -> Map.of(
                    "evidence_key", rs.getString("evidence_key"),
                    "source_domain", value(rs.getString("source_domain")),
                    "lineage_digest", value(rs.getString("lineage_digest")),
                    "final_status", rs.getString("final_status"),
                    "typed_status", rs.getString("typed_status")), cell.id());
            auditCells.add(Map.of("cell_key", cell.key(), "cell_version", cell.version(), "evidence", evidence));
        }
        Map<String, Object> digestInput = Map.of("schema_version", "research-evidence-audit-input.v1", "cells", auditCells);
        String inputDigest = canonicalizer.domainSeparatedDigest(AUDIT_DIGEST_DOMAIN, digestInput);
        Map<String, Object> auditInput = new LinkedHashMap<>(digestInput);
        auditInput.put("input_digest", inputDigest);
        return createRoleTask(run, cells, waveNo, "EVIDENCE_AUDIT", "evidence-audit:" + inputDigest.substring(7, 39),
                Map.of("allowed_tools", List.of(), "audit_input", auditInput));
    }

    private ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskizeDiscovery(String runId, int waveNo) {
        RunScope run = runScope(runId);
        List<CellScope> cells = verifiedCells(runId);
        int planRevision = cells.stream().mapToInt(CellScope::planRevision).max().orElse(1);
        int entityVersion = cells.stream().mapToInt(CellScope::entitySetVersion).max().orElse(1);
        List<?> requiredFindings = run.researchIntent().get("constraints") instanceof List<?> list ? list : List.of();
        Map<String, Object> digestInput = new LinkedHashMap<>();
        digestInput.put("schema_version", "research-discovery-input.v1");
        digestInput.put("question", run.question());
        digestInput.put("required_findings", requiredFindings);
        digestInput.put("plan_revision", planRevision);
        digestInput.put("entity_set_version", entityVersion);
        digestInput.put("max_cells", 80);
        digestInput.put("allowed_source_ids", run.sourceIds());
        String inputDigest = canonicalizer.domainSeparatedDigest("research-discovery-input.v1", digestInput);
        Map<String, Object> discoveryInput = new LinkedHashMap<>(digestInput);
        discoveryInput.put("input_digest", inputDigest);
        String logicalKey = "wide-discovery:" + inputDigest.substring(7, 39);
        Map<String, Long> budget = zeroBudget();
        ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(
                new ResearchAgentTaskService.CreateTaskCommand(
                        run.id(), logicalKey, logicalKey, waveNo, "WIDE_DISCOVERY", "scope-proposal", "branch-main",
                        planRevision, entityVersion, List.of(), new LinkedHashMap<String, Object>(budget), List.of(),
                        new ResearchAgentTaskService.TaskExecutionContext(
                                "research-internal", Map.of("source_scope", run.sourceIds().stream()
                                        .map(sourceId -> Map.of("source_id", sourceId, "title", sourceId)).toList()),
                                Map.of("allowed_tools", List.of(), "discovery_input", discoveryInput),
                                run.researchIntent(), run.controlPack())));
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
        return new ResearchAgentTaskCoordinatorService.CoordinatorReceipt(
                existed ? 0 : 1, existed ? 1 : 0, outbox.idempotentReplay() ? 0 : 1, 0);
    }

    private ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskizeSynthesis(
            String runId,
            int waveNo,
            RoleResult audit
    ) {
        RunScope run = runScope(runId);
        List<CellScope> cells = verifiedCells(runId);
        List<Map<String, Object>> synthesisCells = cells.stream().map(cell -> Map.<String, Object>of(
                "cell_key", cell.key(),
                "candidate_value", cell.value(),
                "evidence_keys", cell.evidenceKeys(),
                "guarded", false)).toList();
        String ledgerDigest = canonicalizer.domainSeparatedDigest(LEDGER_DIGEST_DOMAIN, synthesisCells);
        Map<String, Object> synthesisInput = Map.of(
                "question", run.question(),
                "ledger_digest", ledgerDigest,
                "audit_digest", audit.digest(),
                "cells", synthesisCells,
                "limitations", List.of());
        return createRoleTask(run, cells, waveNo, "SYNTHESIS", "synthesis:" + ledgerDigest.substring(7, 39),
                Map.of("allowed_tools", List.of(), "synthesis_input", synthesisInput));
    }

    private ResearchAgentTaskCoordinatorService.CoordinatorReceipt createRoleTask(
            RunScope run,
            List<CellScope> cells,
            int waveNo,
            String role,
            String logicalKey,
            Map<String, Object> queryPolicy
    ) {
        if (cells.isEmpty() || cells.size() > 80) throw new BusinessException(
                "RESEARCH_AGENT_ROLE_BARRIER_INVALID", "Role task requires one to eighty verified cells");
        Map<String, Long> budget = roleBudget(role);
        ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(
                new ResearchAgentTaskService.CreateTaskCommand(
                        run.id(), logicalKey, logicalKey, waveNo, role, "canonical-ledger", "branch-main",
                        cells.stream().mapToInt(CellScope::planRevision).max().orElse(1),
                        cells.stream().mapToInt(CellScope::entitySetVersion).max().orElse(1),
                        cells.stream().map(CellScope::key).toList(), new LinkedHashMap<String, Object>(budget),
                        cells.stream().map(cell -> new ResearchAgentTaskService.TargetCellBinding(cell.key(), cell.version())).toList(),
                        new ResearchAgentTaskService.TaskExecutionContext(
                                "research-internal", Map.of("source_scope", List.of()), queryPolicy,
                                run.researchIntent(), run.controlPack())));
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
        return new ResearchAgentTaskCoordinatorService.CoordinatorReceipt(
                existed ? 0 : 1, existed ? 1 : 0, outbox.idempotentReplay() ? 0 : 1, cells.size());
    }

    private RunScope runScope(String runId) {
        return jdbcTemplate.query("""
                select id, question, research_intent_json, control_pack_json, source_scope_json
                from research_run where id = ? for update
                """, rs -> rs.next() ? new RunScope(
                rs.getString(1), rs.getString(2), readMap(rs.getString(3)), readMap(rs.getString(4)),
                readStringList(rs.getString(5))) : null,
                runId);
    }

    private List<CellScope> verifiedCells(String runId) {
        return jdbcTemplate.query("""
                select id, cell_key, cell_version, plan_revision, entity_set_version,
                       candidate_value, evidence_refs_json
                from research_cell where research_run_id = ? and cell_status = 'VERIFIED'
                order by cell_key
                """, (rs, rowNum) -> new CellScope(
                rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                rs.getString(6), readStringList(rs.getString(7))), runId);
    }

    private RoleResult latestRoleResult(String runId, String role) {
        return jdbcTemplate.query("""
                select result_status, result_digest from research_agent_role_result
                where research_run_id = ? and role = ? order by created_at desc, id desc limit 1
                """, rs -> rs.next() ? new RoleResult(rs.getString(1), rs.getString(2)) : null, runId, role);
    }

    private boolean activeRoleTask(String runId, String role) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task
                where research_run_id = ? and role = ?
                  and status in ('PENDING','CLAIMED','RUNNING','RETRY_WAIT','EXPIRED')
                """, Integer.class, runId, role);
        return count != null && count > 0;
    }

    private Map<String, Long> zeroBudget() {
        return Map.of("llm_calls", 0L, "search_calls", 0L, "fetch_calls", 0L, "read_calls", 0L,
                "extract_calls", 0L, "evidence_cards", 0L, "candidates_submitted", 0L,
                "evidence_appended", 0L, "candidate_merges_accepted", 0L, "candidate_merges_rejected", 0L);
    }

    private Map<String, Long> roleBudget(String role) {
        Map<String, Long> budget = new LinkedHashMap<>(zeroBudget());
        if ("SYNTHESIS".equals(role)) budget.put("llm_calls", 1L);
        return Map.copyOf(budget);
    }

    private Map<String, Object> readMap(String raw) {
        try { return objectMapper.readValue(raw, new TypeReference<>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Run role scope JSON is invalid", exception); }
    }
    private List<String> readStringList(String raw) {
        try { return objectMapper.readValue(raw, new TypeReference<>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Cell evidence JSON is invalid", exception); }
    }
    private String value(String value) { return value == null ? "" : value; }

    record WorkflowReceipt(String outcome, ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskization) { }
    private record RoleResult(String status, String digest) { }
    private record RunScope(String id, String question, Map<String, Object> researchIntent,
                            Map<String, Object> controlPack, List<String> sourceIds) { }
    private record CellScope(String id, String key, int version, int planRevision, int entitySetVersion,
                             String value, List<String> evidenceKeys) { }
}
