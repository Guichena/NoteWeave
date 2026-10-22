package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Feature-gated Audit -> Synthesis orchestration after the canonical cell barrier settles. */
@Service
class ResearchAgentRoleWorkflowService {
    private static final String AUDIT_DIGEST_DOMAIN = "research-evidence-audit-input.v1";
    private static final int MAX_STALE_AUDIT_REGENERATIONS = 3;
    private static final String LEDGER_DIGEST_DOMAIN = "research-synthesis-ledger.v1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentTaskService taskService;
    private final ResearchBudgetAndCheckpointService budgetService;
    private final ResearchAgentCommandOutboxService outboxService;
    private final ResearchAgentCompletionCanonicalizer canonicalizer;
    private final boolean auditEnabled;
    private final boolean synthesisEnabled;
    private final ResearchAgentFeatureFlagService featureFlags;
    private final ResearchContextV2Gate contextGate;

    ResearchAgentRoleWorkflowService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentTaskService taskService,
            ResearchBudgetAndCheckpointService budgetService,
            ResearchAgentCommandOutboxService outboxService,
            ResearchAgentCompletionCanonicalizer canonicalizer,
            ResearchAgentFeatureFlagService featureFlags,
            ResearchContextV2Gate contextGate,
            @Value("${noteweave.research.evidence-audit-v1:true}") boolean auditEnabled,
            @Value("${noteweave.research.worker-synthesis-v1:false}") boolean synthesisEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.taskService = taskService;
        this.budgetService = budgetService;
        this.outboxService = outboxService;
        this.canonicalizer = canonicalizer;
        this.featureFlags = featureFlags;
        this.contextGate = contextGate;
        this.auditEnabled = auditEnabled;
        this.synthesisEnabled = synthesisEnabled;
    }

    WorkflowReceipt advancePendingAudit(String runId, int waveNo) {
        if (!auditEnabled || !featureFlags.enabledForRun(runId, ResearchAgentFeatureFlagService.EVIDENCE_AUDIT)) return new WorkflowReceipt("NO_AUDIT_PENDING", null);
        ResearchAuditRepairDecisions.settle(jdbcTemplate, runId, ResearchAgentGapProjectionService.repairLimit(jdbcTemplate, runId));
        List<CellScope> pending = jdbcTemplate.query("""
                select id, cell_key, cell_version, plan_revision, entity_set_version, candidate_value, evidence_refs_json
                from research_cell where research_run_id = ? and semantic_audit_status = 'PENDING' and cell_status <> 'FROZEN'
                order by cell_key
                """, (rs, n) -> new CellScope(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), value(rs.getString(6)),
                rs.getString(7) == null ? List.of() : readStringList(rs.getString(7))), runId);
        if (pending.isEmpty()) return new WorkflowReceipt("NO_AUDIT_PENDING", null);
        var taskization = taskizeAudit(runScope(runId), pending, waveNo + 1);
        return new WorkflowReceipt(taskization == null ? "NO_AUDIT_PENDING" : "AUDIT_TASKIZED", taskization);
    }

    WorkflowReceipt advance(String runId, int waveNo) {
        if (!auditActive(runId)) {
            // 语义审计关闭时直接成文：单元格已经过引用、数字与来源的确定性检查
            return advanceSynthesis(runId, waveNo, NO_AUDIT);
        }
        RoleResult audit = latestRoleResult(runId, "EVIDENCE_AUDIT");
        if (audit == null) {
            if (activeRoleTask(runId, "EVIDENCE_AUDIT")) return new WorkflowReceipt("AUDIT_PENDING", null);
            var taskization = taskizeAudit(runId, waveNo + 1);
            return new WorkflowReceipt(taskization == null ? "AUDIT_BLOCKED" : "AUDIT_TASKIZED", taskization);
        }
        if (!"PASS".equals(audit.status())) return new WorkflowReceipt("AUDIT_BLOCKED", null);
        return advanceSynthesis(runId, waveNo, audit);
    }

    /** 语义审计未开启时成文输入里的审计摘要占位值，只用于输入与结果的一致性比对。 */
    private static final RoleResult NO_AUDIT = new RoleResult("NOT_RUN", "sha256:" + "0".repeat(64));

    private boolean auditActive(String runId) {
        return auditEnabled && featureFlags.enabledForRun(runId, ResearchAgentFeatureFlagService.EVIDENCE_AUDIT);
    }

    private WorkflowReceipt advanceSynthesis(String runId, int waveNo, RoleResult audit) {
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
        if (new ResearchRequirementCoverageService(jdbcTemplate, objectMapper).fullCompletion(runId) && finishedCurrentSynthesis(runId))
            return new WorkflowReceipt("FALLBACK_READY", null);
        if (!new ResearchDiscoveryBudget(jdbcTemplate, objectMapper).allows(runId, Map.of("llm_calls", 2L), 1, "SYNTHESIS"))
            return new WorkflowReceipt("FALLBACK_READY", null);
        return new WorkflowReceipt("SYNTHESIS_TASKIZED", taskizeSynthesis(runId, waveNo + 2, audit, List.of()));
    }

    /**
     * 带局限收尾前的综合撰写：只针对已核验的单元格成文，未解决的单元格作为局限交给模型说明。
     * 返回 SYNTHESIS_TASKIZED 或 SYNTHESIS_PENDING 时调用方先等待；SYNTHESIS_READY 时报告用综合结果；
     * FALLBACK_READY 时照旧用模板报告。综合任务已经结束却没有通过校验的结果时也退回模板，不会反复派发。
     */
    WorkflowReceipt advancePartialSynthesis(String runId, int waveNo, List<String> limitations) {
        if (!synthesisEnabled || !featureFlags.enabledForRun(runId, ResearchAgentFeatureFlagService.WORKER_SYNTHESIS)) {
            return new WorkflowReceipt("FALLBACK_READY", null);
        }
        Integer validated = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_synthesis_candidate
                where research_run_id = ? and status = 'VALIDATED'
                """, Integer.class, runId);
        if (validated != null && validated > 0) return new WorkflowReceipt("SYNTHESIS_READY", null);
        if (activeRoleTask(runId, "SYNTHESIS")) return new WorkflowReceipt("SYNTHESIS_PENDING", null);
        Integer finished = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task
                where research_run_id = ? and role = 'SYNTHESIS' and status in ('SUBMITTED', 'FAILED', 'CANCELLED')
                """, Integer.class, runId);
        if (finished != null && finished > 0 && (!new ResearchRequirementCoverageService(jdbcTemplate, objectMapper).fullCompletion(runId)
                || finishedCurrentSynthesis(runId))) return new WorkflowReceipt("FALLBACK_READY", null);
        // 带局限时总会有单元格没通过审计（冲突、缺口），整体审计结论通常不是 PASS；
        // 成文只用 research_formal_eligible_cell 里逐格审计通过的单元格，所以只要求审计已经执行过
        RoleResult audit = auditActive(runId) ? latestRoleResult(runId, "EVIDENCE_AUDIT") : NO_AUDIT;
        if (audit == null || reportCells(runId).isEmpty()) {
            return new WorkflowReceipt("FALLBACK_READY", null);
        }
        if (!new ResearchDiscoveryBudget(jdbcTemplate, objectMapper).allows(runId, Map.of("llm_calls", 2L), 1, "SYNTHESIS"))
            return new WorkflowReceipt("FALLBACK_READY", null);
        return new WorkflowReceipt("SYNTHESIS_TASKIZED", taskizeSynthesis(runId, waveNo + 1, audit, limitations));
    }

    private ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskizeAudit(String runId, int waveNo) {
        return taskizeAudit(runScope(runId), verifiedCells(runId), waveNo);
    }

    private ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskizeAudit(RunScope run, List<CellScope> available, int waveNo) {
        String runId = run.id();
        List<CellScope> cells = new ArrayList<>();
        List<Map<String, Object>> auditCells = new ArrayList<>();
        var clusterService = new ResearchClaimClusterService(jdbcTemplate, objectMapper);
        var coverage = new ResearchRequirementCoverageService(jdbcTemplate, objectMapper);
        var semantics = new ResearchFactSemanticCoverage(jdbcTemplate);
        boolean factSemantics = coverage.modernState(runId) && coverage.enforcesFacts(runId);
        int pairCount = 0;
        for (CellScope cell : available) {
            if (!cells.isEmpty() && (cell.planRevision() != cells.get(0).planRevision() || cell.entitySetVersion() != cells.get(0).entitySetVersion())) continue;
            List<Map<String, Object>> claimClusters = factSemantics ? coverage.auditFacts(runId, cell.key(), cell.planRevision(), cell.entitySetVersion()).stream()
                    .map(fact -> Map.<String, Object>of("cluster_key", fact.get("fact_id"), "claim_text", fact.get("claim_text"), "evidence_keys", fact.get("evidence_keys"),
                            "evidence_quotes", fact.get("evidence_quotes"), "source_scopes", fact.get("source_scopes"))).toList()
                    : clusterService.clusters(runId, cell.key());
            List<Map<String, String>> pendingPairs = factSemantics ? semantics.pendingPairs(claimClusters.stream().map(item -> String.valueOf(item.get("cluster_key"))).toList(),
                    semantics.relations(runId, cell.key()), Math.max(0, 20 - pairCount)) : List.of();
            int pairs = factSemantics ? pendingPairs.size() : claimClusters.size() * (claimClusters.size() - 1) / 2;
            if (!cells.isEmpty() && (pairCount + pairs > 20 || canonicalizer.canonicalJsonValue(auditCells).length() > 160000)) break;
            Set<String> keys = new java.util.TreeSet<>(factSemantics ? List.of() : cell.evidenceKeys());
            for (var cluster : claimClusters) for (Object key : (List<?>) cluster.get("evidence_keys")) keys.add(String.valueOf(key));
            List<Map<String, Object>> evidence = new ArrayList<>();
            for (String key : keys) evidence.addAll(jdbcTemplate.query("""
                    select evidence.evidence_key, evidence.source_domain, evidence.lineage_digest,
                           coalesce(validation.final_status, 'QUALIFIED') as final_status,
                           coalesce(validation.deterministic_status, 'PASS') as typed_status
                    from source_evidence evidence
                    left join research_evidence_validation validation on validation.evidence_id = evidence.id
                    where evidence.research_run_id = ? and evidence.evidence_key = ? order by evidence.evidence_key
                    """, (rs, rowNum) -> Map.of(
                    "evidence_key", rs.getString("evidence_key"),
                    "source_domain", value(rs.getString("source_domain")),
                    "lineage_digest", value(rs.getString("lineage_digest")),
                    "final_status", rs.getString("final_status"),
                    "typed_status", rs.getString("typed_status")), runId, key));
            int revision = jdbcTemplate.queryForObject("select semantic_revision from research_cell where id = ?", Integer.class, cell.id());
            List<String> descriptions = jdbcTemplate.queryForList("select col.description from research_column col join research_cell cell on cell.research_run_id = col.research_run_id and cell.column_key = col.column_key where cell.id = ?",
                    String.class, cell.id());
            Map<String, Object> auditCell = new LinkedHashMap<>(Map.of("cell_key", cell.key(), "cell_version", cell.version(), "semantic_revision", revision,
                    "column_description", descriptions.isEmpty() ? "" : value(descriptions.get(0)), "evidence", evidence,
                    "clusters", factSemantics ? claimClusters : claimClusters.stream().map(cluster -> Map.of("cluster_key", cluster.get("cluster_key"),
                    "claim_text", cluster.get("claim_text"), "evidence_keys", cluster.get("evidence_keys"))).toList()));
            if (factSemantics) auditCell.put("pairs", pendingPairs);
            auditCells.add(Map.copyOf(auditCell));
            cells.add(cell); pairCount += pairs;
            if (factSemantics && pairCount >= 20) break;
        }
        Map<String, Object> digestInput = Map.of("schema_version", "research-evidence-audit-input.v2", "cells", auditCells);
        String inputDigest = canonicalizer.domainSeparatedDigest(AUDIT_DIGEST_DOMAIN, digestInput);
        Map<String, Object> auditInput = new LinkedHashMap<>(digestInput);
        auditInput.put("input_digest", inputDigest);
        if (factSemantics) auditInput.put("fact_semantics_version", 1);
        Map<String, Object> policy = Map.of("allowed_tools", List.of(), "audit_input", auditInput);
        if (!new ResearchDiscoveryBudget(jdbcTemplate, objectMapper).allows(runId, roleBudget("EVIDENCE_AUDIT", policy), 1, "EVIDENCE_AUDIT")) {
            // 审计不能执行时保留未判定状态，不允许通过正式成文资格。
            for (CellScope cell : cells) jdbcTemplate.update("update research_cell set semantic_audit_status = 'NOT_RUN' where id = ? and semantic_audit_status = 'PENDING'", cell.id());
            jdbcTemplate.update("update research_discovery_loop set phase = 'STOPPED', stop_reason = 'LOOP_BUDGET_EXHAUSTED' where research_run_id = ?", runId);
            return null;
        }
        // 审计任务因目标过期被取消后，相同输入会算出相同的幂等键，直接重放已取消的任务，研究会停住；
        // 按已取消的次数换一个键重新创建，最多重试 3 次
        Integer staleCancellations = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task
                where research_run_id = ? and role = 'EVIDENCE_AUDIT' and status = 'CANCELLED'
                  and terminal_reason = 'TARGET_STALE'
                """, Integer.class, runId);
        int regeneration = staleCancellations == null ? 0 : staleCancellations;
        if (regeneration > MAX_STALE_AUDIT_REGENERATIONS) return null;
        String logicalKey = "evidence-audit:" + inputDigest.substring(7, 39)
                + (regeneration == 0 ? "" : ":regen-" + regeneration);
        return createRoleTask(run, cells, waveNo, "EVIDENCE_AUDIT", logicalKey, policy);
    }

    private ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskizeSynthesis(
            String runId,
            int waveNo,
            RoleResult audit,
            List<String> limitations
    ) {
        RunScope run = runScope(runId);
        List<CellScope> cells = reportCells(runId);
        Map<String, String> labels = ResearchAgentIncrementalFinalizationService.matrixLabels(jdbcTemplate, runId);
        var coverage = new ResearchRequirementCoverageService(jdbcTemplate, objectMapper);
        // 只给编号和一句结论时，模型不知道这一格属于哪个对象、哪一列，容易把一句话的适用范围说大；
        // 带上名称和几段支撑原文，成文时能按原文的范围下结论
        List<Map<String, Object>> synthesisCells = cells.stream().map(cell -> {
            String[] parts = cell.key().split(":", 2);
            Map<String, Object> item = new LinkedHashMap<>();
            // 单元格的值只取了证据最多的一组结论；同一单元格其余通过资格校验的结论也交给成文，
            // 需要列举多项或补充细节时可以引用，引用的证据编号仍须属于这个单元格
            List<String> evidenceKeys = withRelatedEvidence(runId, cell);
            item.put("cell_key", cell.key());
            item.put("candidate_value", cell.value());
            item.put("evidence_keys", evidenceKeys);
            item.put("guarded", false);
            item.put("entity_label", labels.getOrDefault(parts[0], ""));
            item.put("column_label", parts.length > 1 ? labels.getOrDefault(parts[1], "") : "");
            if (coverage.enforcesFacts(runId)) item.put("facts", coverage.synthesisFacts(runId, cell.key(), cell.planRevision(), cell.entitySetVersion()));
            // 每条原文带上自己的证据编号：只给两份互不对应的列表时，模型分不清哪个编号对应哪句原文，
            // 同一格里常把只讲 gauge 的引用挂到列举四种类型的句子上
            List<Map<String, String>> evidenceQuotes = evidenceQuotes(runId, evidenceKeys);
            item.put("evidence_quotes", evidenceQuotes);
            item.put("supporting_quotes", evidenceQuotes.stream().map(quote -> quote.get("quote")).toList());
            return item;
        }).toList();
        Map<String, Object> synthesisInput = new LinkedHashMap<>();
        if (ResearchCompletionGapPolicy.enabled(jdbcTemplate,runId)) synthesisInput.put(ResearchCompletionGapPolicy.FLAG,1);
        List<String> knownLimitations = new ArrayList<>(limitations);
        if (coverage.enforcesFacts(runId)) {
            Map<String, Object> progress = coverage.readModel(runId);
            synthesisInput.put("requirement_coverage", progress);
            if (coverage.modernState(runId)) {
                synthesisInput.put("source_scope_version", 1);
                synthesisInput.put("report_upload_version", 1);
                synthesisInput.put("statement_support_version", 1);
                if (coverage.improvedCompletion(runId)) synthesisInput.put("report_coverage_version", 1);
                if (coverage.fullCompletion(runId)) {
                    synthesisInput.put("report_recovery_version", 1);
                    synthesisInput.put("report_call_meter_version",1);
                    synthesisInput.put("report_feedback_version", 1);
                    if (coverage.answerPolicy(runId)) synthesisInput.put(ResearchAnswerPolicy.FLAG, 1);
                    synthesisInput.put("report_metadata_version", 1);
                    synthesisInput.put("report_feedback_digest", feedbackDigest(runId));
                }
            }
            synthesisInput.put("coverage_digest", canonicalizer.domainSeparatedDigest("research-synthesis-coverage.v1", progress));
            for (Object raw : (List<?>) progress.get("requirements")) {
                Map<?, ?> requirement = (Map<?, ?>) raw;
                if (!"SATISFIED".equals(requirement.get("status"))) knownLimitations.add(
                        "尚未充分回答：" + requirement.get("entity_label") + " / " + requirement.get("text"));
            }
            if (!Boolean.TRUE.equals(progress.get("explicit_definitions_complete")))
                knownLimitations.add("用户明确要求尚未完成解析，当前报告仅包含已核验的发现。");
            // 每批最多两次模型调用；超过预留数量的批次使用有引用的确定性成文。
            long desired = Math.min(16L, Math.max(2L, (synthesisCells.size() + 5L) / 6L * 2L));
            if (coverage.modernState(runId)) desired = Math.min(16L, Math.max(4L, (synthesisCells.size() + 5L) / 6L * 3L));
            if (coverage.improvedCompletion(runId)) desired = Math.min(16L, Math.max(6L, (synthesisCells.size() + 5L) / 6L * 4L));
            // 逐项答案、逐句核验与全量覆盖共用这份上限；实际未使用额度仍释放。
            if (coverage.answerPolicy(runId)) desired = 16L;
            var budget = new ResearchDiscoveryBudget(jdbcTemplate, objectMapper);
            while (desired > 0 && !budget.allows(runId, Map.of("llm_calls", desired), 1, "SYNTHESIS")) desired--;
            synthesisInput.put("narrative_llm_budget", desired);
        }
        synthesisInput.put("question", run.question()); synthesisInput.put("audit_digest", audit.digest());
        synthesisInput.put("cells", synthesisCells); synthesisInput.put("leads", synthesisLeads(runId, labels));
        synthesisInput.put("required_points", requiredPoints(runId));
        synthesisInput.put("limitations", knownLimitations.stream().distinct().toList());
        String ledgerDigest = coverage.enforcesFacts(runId)
                ? canonicalizer.domainSeparatedDigest("research-synthesis-ledger.v2", synthesisInput)
                : canonicalizer.domainSeparatedDigest(LEDGER_DIGEST_DOMAIN, synthesisCells);
        synthesisInput.put("ledger_digest", ledgerDigest);
        return createRoleTask(run, cells, waveNo, "SYNTHESIS", "synthesis:" + ledgerDigest.substring(7, 39),
                Map.of("allowed_tools", List.of(), "synthesis_input", synthesisInput));
    }

    /** 规划给出的专家必查要点，取自当前生效的研究表格规划；没有时为空。 */
    private List<String> requiredPoints(String runId) {
        List<String> plans = jdbcTemplate.queryForList("""
                select plan_json from research_matrix_plan
                where research_run_id = ? and plan_mode = 'LLM_MATRIX' and plan_status = 'ACTIVE'
                order by created_at desc, id desc limit 1
                """, String.class, runId);
        if (plans.isEmpty() || plans.get(0) == null) return List.of();
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(plans.get(0));
            if (node.isTextual()) node = objectMapper.readTree(node.asText());
            List<String> points = new ArrayList<>();
            node.path("brief").path("required_points").forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) points.add(item.asText());
            });
            return List.copyOf(points);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return List.of();
        }
    }

    private String feedbackDigest(String runId) {
        return canonicalizer.domainSeparatedDigest("research-report-feedback-state.v1", new ResearchReportFeedbackService(jdbcTemplate, objectMapper).details(runId, ""));
    }

    private boolean finishedCurrentSynthesis(String runId) {
        String digest = feedbackDigest(runId);
        for (String raw : jdbcTemplate.queryForList("select execution_context_json from research_agent_task where research_run_id = ? and role = 'SYNTHESIS' and status in ('SUBMITTED','FAILED','CANCELLED')", String.class, runId)) {
            try {
                var node = objectMapper.readTree(raw); if (node.isTextual()) node = objectMapper.readTree(node.asText());
                if (digest.equals(node.path("query_policy").path("synthesis_input").path("report_feedback_digest").asText())) return true;
            } catch (JsonProcessingException exception) { throw new BusinessException("RESEARCH_SYNTHESIS_INPUT_INVALID", "报告任务输入无法读取"); }
        }
        return false;
    }

    /** 交给成文的线索最多条数，避免线索挤占已核验结论的篇幅。 */
    private static final int MAX_SYNTHESIS_LEADS = 12;

    /**
     * 没通过核验但有原文出处的单元格线索。成文只在已核验的结论没覆盖到的方面使用，并在句子里写明未经核实；
     * 引用编号只能是线索自己的证据。
     */
    private List<Map<String, Object>> synthesisLeads(String runId, Map<String, String> labels) {
        List<Map<String, Object>> leads = new ArrayList<>();
        for (ResearchCellLevels.Lead lead : new ResearchCellLevels(jdbcTemplate, objectMapper).leads(runId).values()) {
            if (leads.size() >= MAX_SYNTHESIS_LEADS) break;
            String[] parts = lead.cellKey().split(":", 2);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cell_key", lead.cellKey());
            item.put("candidate_value", lead.value());
            item.put("evidence_level", lead.level());
            item.put("evidence_keys", lead.quotes().stream().map(ResearchCellLevels.Quote::evidenceKey).toList());
            item.put("entity_label", labels.getOrDefault(parts[0], ""));
            item.put("column_label", parts.length > 1 ? labels.getOrDefault(parts[1], "") : "");
            List<Map<String, String>> evidenceQuotes = lead.quotes().stream()
                    .filter(quote -> quote.evidenceKey() != null)
                    .map(quote -> Map.of("evidence_key", quote.evidenceKey(),
                            "quote", withTitle(quote.sourceTitle(), quote.quote())))
                    .filter(quote -> !quote.get("quote").isEmpty()).toList();
            item.put("evidence_quotes", evidenceQuotes);
            item.put("supporting_quotes", evidenceQuotes.stream().map(quote -> quote.get("quote")).toList());
            leads.add(item);
        }
        return leads;
    }

    private static final int MAX_SYNTHESIS_EVIDENCE_PER_CELL = 8;

    /** 单元格自身的证据，加上同一单元格其他候选簇里通过资格校验的证据，最多 8 条。 */
    private List<String> withRelatedEvidence(String runId, CellScope cell) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(cell.evidenceKeys());
        var coverage = new ResearchRequirementCoverageService(jdbcTemplate, objectMapper);
        int limit = MAX_SYNTHESIS_EVIDENCE_PER_CELL;
        if (coverage.enabled(runId)) {
            // 跨轮事实优先于同一代表值的重复佐证，避免只读取最后一次候选簇。
            List<String> accumulated = coverage.evidenceKeys(runId, cell.key());
            if (!accumulated.isEmpty()) {
                java.util.LinkedHashSet<String> combined = new java.util.LinkedHashSet<>(accumulated);
                combined.addAll(keys);
                keys = combined;
            }
            return List.copyOf(keys);
        }
        var clusterService = new ResearchClaimClusterService(jdbcTemplate, objectMapper);
        for (Map<String, Object> cluster : clusterService.clusters(runId, cell.key())) {
            Object raw = cluster.get("evidence_keys");
            if (!(raw instanceof List<?> list)) continue;
            for (Object key : list) {
                if (keys.size() >= limit) break;
                String evidenceKey = String.valueOf(key);
                if (!keys.contains(evidenceKey) && qualifiedEvidence(runId, evidenceKey)) keys.add(evidenceKey);
            }
        }
        return List.copyOf(keys);
    }

    private boolean qualifiedEvidence(String runId, String evidenceKey) {
        Integer rejected = jdbcTemplate.queryForObject("""
                select count(*) from source_evidence evidence
                left join research_evidence_validation validation on validation.evidence_id = evidence.id
                where evidence.research_run_id = ? and evidence.evidence_key = ?
                  and (validation.final_status is null or validation.final_status = 'REJECTED'
                       or evidence.relation_type <> 'SUPPORTS')
                """, Integer.class, runId, evidenceKey);
        Integer present = jdbcTemplate.queryForObject(
                "select count(*) from source_evidence where research_run_id = ? and evidence_key = ?",
                Integer.class, runId, evidenceKey);
        return present != null && present > 0 && (rejected == null || rejected == 0);
    }
    // 与 Worker 扩展引用的上限一致，答案的关键名称常在扩展出的后一句里
    private static final int MAX_QUOTE_CHARS = 700;

    private List<Map<String, String>> evidenceQuotes(String runId, List<String> evidenceKeys) {
        List<Map<String, String>> quotes = new ArrayList<>();
        for (String key : evidenceKeys) {
            // 原文前加上来源标题：PEP、JEP、RFC 编号常常只出现在标题里，成文时需要据此写出编号
            jdbcTemplate.query("""
                    select source_title, quote_text from source_evidence
                    where research_run_id = ? and evidence_key = ? and quote_text is not null
                    """, (rs, rowNum) -> withTitle(rs.getString(1), rs.getString(2)), runId, key).stream().findFirst()
                    .filter(quote -> !quote.isEmpty())
                    .ifPresent(quote -> quotes.add(Map.of("evidence_key", key, "quote", quote)));
        }
        return quotes;
    }

    private static String withTitle(String title, String quote) {
        String text = quote == null ? "" : quote.strip();
        if (text.isEmpty()) return "";
        if (text.length() > MAX_QUOTE_CHARS) text = text.substring(0, MAX_QUOTE_CHARS);
        String name = title == null ? "" : title.replaceAll("\\s+", " ").strip();
        return name.isEmpty() || text.contains(name) ? text : "[" + name + "] " + text;
    }

    private ResearchAgentTaskCoordinatorService.CoordinatorReceipt createRoleTask(
            RunScope run,
            List<CellScope> cells,
            int waveNo,
            String role,
            String logicalKey,
            Map<String, Object> queryPolicy
    ) {
        if (new ResearchRequirementCoverageService(jdbcTemplate,objectMapper).fullCompletion(run.id())) {
            queryPolicy = new LinkedHashMap<>(queryPolicy);
            queryPolicy.put("completion_runtime_version",1);
        }
        if (cells.isEmpty() || cells.size() > 80) throw new BusinessException(
                "RESEARCH_AGENT_ROLE_BARRIER_INVALID", "Role task requires one to eighty verified cells");
        Map<String, Long> budget = roleBudget(role, queryPolicy);
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
        RunScope scope = jdbcTemplate.query("""
                select id, coalesce(execution_question, question), research_intent_json,
                       control_pack_json, source_scope_json
                from research_run where id = ? for update
                """, rs -> rs.next() ? new RunScope(
                rs.getString(1), rs.getString(2), readMap(rs.getString(3)), readMap(rs.getString(4)),
                readStringList(rs.getString(5))) : null,
                runId);
        if (scope != null) contextGate.requireReadable(runId);
        return scope;
    }

    private List<CellScope> verifiedCells(String runId) {
        return jdbcTemplate.query("""
                select id, cell_key, cell_version, plan_revision, entity_set_version,
                       candidate_value, evidence_refs_json
                from research_formal_eligible_cell where research_run_id = ? and cell_status = 'VERIFIED'
                order by cell_key
                """, (rs, rowNum) -> new CellScope(
                rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                rs.getString(6), readStringList(rs.getString(7))), runId);
    }

    private List<CellScope> reportCells(String runId) {
        var coverage = new ResearchRequirementCoverageService(jdbcTemplate,objectMapper);
        List<CellScope> result = new ArrayList<>(verifiedCells(runId));
        if (!coverage.fullCompletion(runId) || !coverage.enforcesFacts(runId)) return result;
        var partial = jdbcTemplate.query("""
                select id,cell_key,cell_version,plan_revision,entity_set_version from research_cell
                where research_run_id = ? and cell_status = 'GAP' and active_task_id is null
                  and (? or semantic_audit_status = 'PASS') order by cell_key
                """,(rs,n) -> new CellScope(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getInt(5),"",List.of()),runId,!auditActive(runId));
        for (CellScope cell : partial) {
            var facts = coverage.synthesisFacts(runId,cell.key(),cell.planRevision(),cell.entitySetVersion());
            if (facts.isEmpty()) continue;
            List<String> keys = facts.stream().flatMap(fact -> ((List<?>)fact.get("evidence_keys")).stream()).map(String::valueOf).distinct().toList();
            result.add(new CellScope(cell.id(),cell.key(),cell.version(),cell.planRevision(),cell.entitySetVersion(),String.valueOf(facts.get(0).get("claim_text")),keys));
        }
        return result.stream().sorted(java.util.Comparator.comparing(CellScope::key)).toList();
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

    private Map<String, Long> roleBudget(String role, Map<String, Object> policy) {
        Map<String, Long> budget = new LinkedHashMap<>(zeroBudget());
        // 报告合成允许一次带失败原因的重试
        if ("SYNTHESIS".equals(role)) {
            var input = objectMapper.valueToTree(policy.get("synthesis_input"));
            budget.put("llm_calls", input.path("narrative_llm_budget").asLong(2L));
        }
        if ("EVIDENCE_AUDIT".equals(role)) {
            com.fasterxml.jackson.databind.JsonNode input = objectMapper.valueToTree(policy.get("audit_input"));
            long pairs = 0;
            for (var cell : input.path("cells")) {
                long size = cell.path("clusters").size();
                pairs += cell.has("pairs") ? cell.path("pairs").size() : size * (size - 1) / 2;
            }
            budget.put("llm_calls", Math.min(80L, pairs * 4));
        }
        return Map.copyOf(budget);
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try { return objectMapper.readValue(raw, new TypeReference<>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Run role scope JSON is invalid", exception); }
    }
    private List<String> readStringList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
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
