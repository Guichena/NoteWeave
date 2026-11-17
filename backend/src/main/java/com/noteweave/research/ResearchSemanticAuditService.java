package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 语义判定只作用于任务冻结的候选簇及审计版本，未执行的判定禁止成文。 */
@Service
class ResearchSemanticAuditService {
    private static final Set<String> CHECK_FIELDS = Set.of("cell_key", "left_cluster_key", "right_cluster_key", "verdict", "equivalence_verdict");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ResearchClaimClusterService clusters;

    ResearchSemanticAuditService(JdbcTemplate jdbc, ObjectMapper mapper, ResearchClaimClusterService clusters) {
        this.jdbc = jdbc; this.mapper = mapper; this.clusters = clusters;
    }

    void apply(ResearchAgentCompletionCommitter.TaskRow task, ResearchAgentCompletionEnvelope envelope, String roleResultId) {
        JsonNode input = clusters.read(task.executionContextJson()).path("query_policy").path("audit_input");
        if (!"research-evidence-audit-input.v2".equals(input.path("schema_version").asText())) return;
        JsonNode result = mapper.valueToTree(envelope.roleResult());
        if (!"research-evidence-audit-result.v2".equals(result.path("result_schema_version").asText())
                || !result.path("semantic_checks").isArray()) throw invalid();
        Map<String, JsonNode> cells = new LinkedHashMap<>();
        Map<String, JsonNode[]> expected = new HashMap<>();
        for (JsonNode cell : input.path("cells")) {
            String cellKey = cell.path("cell_key").asText(); cells.put(cellKey, cell);
            JsonNode list = cell.path("clusters");
            if (cell.has("pairs")) {
                Map<String, JsonNode> byKey = new HashMap<>();
                list.forEach(cluster -> byKey.put(cluster.path("cluster_key").asText(), cluster));
                if (!cell.path("pairs").isArray() || cell.path("pairs").size() > 20) throw invalid();
                for (JsonNode requested : cell.path("pairs")) {
                    String left = requested.path("left_cluster_key").asText(), right = requested.path("right_cluster_key").asText();
                    if (requested.size() != 2 || left.equals(right) || !byKey.containsKey(left) || !byKey.containsKey(right)
                            || expected.put(pair(cellKey, left, right), new JsonNode[] {byKey.get(left), byKey.get(right)}) != null) throw invalid();
                }
            } else for (int i = 0; i < list.size(); i++) for (int j = 0; j < i; j++)
                expected.put(pair(cellKey, list.get(j).path("cluster_key").asText(), list.get(i).path("cluster_key").asText()), new JsonNode[] {list.get(j), list.get(i)});
            Integer current = jdbc.queryForObject("select count(*) from research_cell where research_run_id = ? and cell_key = ? and cell_version = ? and semantic_revision = ?",
                    Integer.class, task.runId(), cellKey, cell.path("cell_version").asInt(), cell.path("semantic_revision").asInt());
            if (current == null || current != 1) throw new BusinessException("RESEARCH_AGENT_ROLE_RESULT_STALE", "单元格或语义审计版本已变化");
        }
        Map<String, List<JsonNode>> byCell = new HashMap<>();
        Set<String> observed = new HashSet<>();
        int executed = 0;
        for (JsonNode check : result.path("semantic_checks")) {
            Set<String> fields = new HashSet<>(); check.fieldNames().forEachRemaining(fields::add);
            String key = pair(check.path("cell_key").asText(), check.path("left_cluster_key").asText(), check.path("right_cluster_key").asText());
            if (!check.isObject() || !fields.equals(CHECK_FIELDS) || !observed.add(key) || !expected.containsKey(key)
                    || !Set.of("CONTRADICTORY", "NOT_CONTRADICTORY", "NOT_RUN").contains(check.path("verdict").asText())
                    || !Set.of("EQUIVALENT", "NOT_EQUIVALENT", "NOT_RUN").contains(check.path("equivalence_verdict").asText())) throw invalid();
            if (!"NOT_RUN".equals(check.path("verdict").asText())) {
                executed++;
                if ("NOT_RUN".equals(check.path("equivalence_verdict").asText())
                        || ("CONTRADICTORY".equals(check.path("verdict").asText()) && "EQUIVALENT".equals(check.path("equivalence_verdict").asText()))) throw invalid();
            }
            byCell.computeIfAbsent(check.path("cell_key").asText(), ignored -> new ArrayList<>()).add(check);
        }
        if (!observed.equals(expected.keySet()) || envelope.budgetUsage().get("llm_calls") < executed) throw invalid();
        Set<String> repairTargets = new HashSet<>();
        result.path("targets").forEach(target -> repairTargets.add(target.path("cell_key").asText()));
        boolean anyNotRun = false, anyRepair = false;
        for (var entry : cells.entrySet()) {
            String cellKey = entry.getKey(); JsonNode cell = entry.getValue();
            List<JsonNode> checks = byCell.getOrDefault(cellKey, List.of());
            boolean notRun = checks.stream().anyMatch(check -> "NOT_RUN".equals(check.path("verdict").asText()));
            boolean contradictory = checks.stream().anyMatch(check -> "CONTRADICTORY".equals(check.path("verdict").asText()));
            boolean badEvidence = !cell.path("evidence").isArray() || cell.path("evidence").isEmpty();
            for (JsonNode evidence : cell.path("evidence")) {
                badEvidence |= !Set.of("QUALIFIED", "QUALIFIED_UNBOUND").contains(evidence.path("final_status").asText())
                        || evidence.path("source_domain").asText().isBlank() || evidence.path("lineage_digest").asText().isBlank()
                        || "CONTRADICTED".equals(evidence.path("typed_status").asText());
            }
            if ((notRun || contradictory || badEvidence) && !repairTargets.contains(cellKey)) throw invalid();
            anyNotRun |= notRun; anyRepair |= contradictory || badEvidence;
            String status = notRun ? "NOT_RUN" : contradictory ? "CONFLICT" : badEvidence ? "BLOCKED" : "PASS";
            jdbc.update("update research_cell set semantic_audit_status = ?, updated_at = current_timestamp where research_run_id = ? and cell_key = ?",
                    status, task.runId(), cellKey);
            for (JsonNode check : checks) {
                jdbc.update("""
                        insert into research_semantic_check(id, research_run_id, task_id, cell_key, cell_version,
                            semantic_revision, left_cluster_key, right_cluster_key, verdict, equivalence_verdict)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Ids.newId(), task.runId(), task.id(), cellKey, cell.path("cell_version").asInt(), cell.path("semantic_revision").asInt(),
                        check.path("left_cluster_key").asText(), check.path("right_cluster_key").asText(), check.path("verdict").asText(), check.path("equivalence_verdict").asText());
                if ("CONTRADICTORY".equals(check.path("verdict").asText())) {
                    recordConflict(task, cell, expected.get(pair(cellKey, check.path("left_cluster_key").asText(), check.path("right_cluster_key").asText())), roleResultId);
                }
            }
            boolean complete = true;
            if ("PASS".equals(status) && cell.has("pairs")) {
                var semantics = new ResearchFactSemanticCoverage(jdbc);
                List<String> ids = new ArrayList<>(); cell.path("clusters").forEach(cluster -> ids.add(cluster.path("cluster_key").asText()));
                complete = semantics.allChecked(ids, semantics.relations(task.runId(), cellKey));
                if (!complete) jdbc.update("update research_cell set semantic_audit_status = 'PENDING' where research_run_id = ? and cell_key = ?", task.runId(), cellKey);
            }
            if ("PASS".equals(status) && complete) {
                resolveOlderConflicts(task.runId(), cellKey, cell.path("semantic_revision").asInt());
                approveEquivalentRepair(task, cellKey, cell, checks, roleResultId);
            }
        }
        String expectedStatus = anyNotRun ? "BLOCKED" : anyRepair ? "REPAIR_REQUIRED" : "PASS";
        if (!expectedStatus.equals(result.path("status").asText())) throw invalid();
        if (input.path("fact_semantics_version").asInt() == 1) new ResearchRequirementCoverageService(jdbc, mapper).reconcileAuditedCoverage(task.runId());
    }

    private void recordConflict(ResearchAgentCompletionCommitter.TaskRow task, JsonNode cell, JsonNode[] pair, String roleResultId) {
        Set<String> keys = new java.util.TreeSet<>();
        for (JsonNode cluster : pair) cluster.path("evidence_keys").forEach(key -> keys.add(key.asText()));
        Map<String, Object> notes = Map.of("conflict_kind", "SEMANTIC_CONTRADICTION", "semantic_revision", cell.path("semantic_revision").asInt(),
                "cell_version", cell.path("cell_version").asInt(), "role_result_id", roleResultId,
                "left_claim", pair[0].path("claim_text").asText(), "right_claim", pair[1].path("claim_text").asText());
        jdbc.update("""
                insert into research_verifier_decision(id, research_run_id, decision_scope, decision_type, reason_code,
                    target_id, evidence_ids_json, action_text, decision_status, notes_json)
                values (?, ?, 'CELL', 'GLOBAL_CONFLICT', 'SEMANTIC_CONTRADICTION', ?, ?, ?, 'OPEN', ?)
                """, Ids.newId(), task.runId(), cell.path("cell_key").asText(), Json.write(mapper, List.copyOf(keys)),
                "语义冲突，需要定向反证", Json.write(mapper, notes));
        jdbc.update("update research_cell set cell_status = 'CONFLICTED', last_verifier_decision = 'GLOBAL_CONFLICT:SEMANTIC_CONTRADICTION', updated_at = current_timestamp where research_run_id = ? and cell_key = ? and cell_status <> 'FROZEN'",
                task.runId(), cell.path("cell_key").asText());
        new ResearchRequirementCoverageService(jdbc, mapper).invalidateEvidence(task.runId(), cell.path("cell_key").asText(), keys, "SEMANTIC_CONTRADICTION");
    }

    private void resolveOlderConflicts(String runId, String cellKey, int revision) {
        List<Map<String, Object>> traces = jdbc.query("select id, notes_json from research_verifier_decision where research_run_id = ? and target_id = ? and decision_type = 'GLOBAL_CONFLICT' and reason_code = 'SEMANTIC_CONTRADICTION' and decision_status in ('OPEN','EXHAUSTED')",
                (rs, n) -> Map.of("id", rs.getString(1), "notes", clusters.read(rs.getString(2))), runId, cellKey);
        for (Map<String, Object> trace : traces) if (((JsonNode) trace.get("notes")).path("semantic_revision").asInt() < revision) {
            jdbc.update("update research_verifier_decision set decision_status = 'RESOLVED' where id = ?", trace.get("id"));
        }
    }

    private void approveEquivalentRepair(ResearchAgentCompletionCommitter.TaskRow task, String cellKey, JsonNode cell, List<JsonNode> checks, String roleResultId) {
        String runId = task.runId();
        var coverage = new ResearchRequirementCoverageService(jdbc, mapper);
        boolean modern = coverage.modernState(runId);
        List<Map<String, Object>> decisions = jdbc.query("""
                select id, evidence_ids_json, notes_json from research_verifier_decision where research_run_id = ? and target_id = ?
                    and decision_type = 'QUORUM_REPAIR_REQUIRED' and reason_code = 'QUORUM_VALUE_CONFLICT' and decision_status = 'OPEN'
                """, (rs, n) -> Map.of("id", rs.getString(1), "keys", clusters.read(rs.getString(2)),
                        "notes", rs.getString(3) == null ? mapper.createObjectNode() : clusters.read(rs.getString(3))), runId, cellKey);
        if (decisions.isEmpty() || (!modern && (checks.isEmpty() || checks.stream().anyMatch(check -> !"EQUIVALENT".equals(check.path("equivalence_verdict").asText()))))) return;
        for (var decision : decisions) {
            if (modern && !coverage.equivalentQuorum(runId, cellKey, task.planRevision(), task.entitySetVersion(),
                    ((JsonNode) decision.get("notes")).path("quorum_group_key").asText())) continue;
            Set<String> currentKeys = new HashSet<>();
            cell.path("clusters").forEach(cluster -> cluster.path("evidence_keys").forEach(key -> currentKeys.add(key.asText())));
            Set<String> decisionKeys = new HashSet<>();
            ((JsonNode) decision.get("keys")).forEach(key -> decisionKeys.add(key.asText()));
            if (decisionKeys.isEmpty() || !currentKeys.containsAll(decisionKeys)) continue;
            List<ResearchSourceIndependence.Source> sources = new ArrayList<>();
            ((JsonNode) decision.get("keys")).forEach(key -> sources.addAll(jdbc.query("select source_domain, lineage_digest, quote_text, support_score_ppm from source_evidence where research_run_id = ? and evidence_key = ?",
                    (rs, n) -> new ResearchSourceIndependence.Source(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)), runId, key.asText())));
            if (ResearchSourceIndependence.score(sources).independentSources() < 2) continue;
            jdbc.update("""
                    update research_verifier_decision set reason_code = 'SEMANTIC_EQUIVALENCE_APPROVED', evidence_ids_json = '[]', notes_json = ? where id = ?
                    """, Json.write(mapper, Map.of("role_result_id", roleResultId, "semantic_revision", cell.path("semantic_revision").asInt())), decision.get("id"));
        }
    }

    private static String pair(String cell, String left, String right) { return cell + "|" + (left.compareTo(right) < 0 ? left + "|" + right : right + "|" + left); }
    private static BusinessException invalid() { return new BusinessException("RESEARCH_SEMANTIC_AUDIT_INVALID", "语义判定缺失、格式不合规或与冻结候选簇不一致"); }
}
