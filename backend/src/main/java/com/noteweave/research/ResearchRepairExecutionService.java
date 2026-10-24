package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 查询历史与原子事实反证，沿用任务完成事务和摘要。 */
@Service
public class ResearchRepairExecutionService {
    private static final Set<String> STRATEGIES = Set.of("DIRECT", "COUNTER_EVIDENCE", "BROADEN", "NARROW", "SWITCH_LANGUAGE", "SWITCH_SOURCE_TYPE");
    private static final Set<String> VERDICTS = Set.of("REFUTED", "NOT_REFUTED", "NO_COUNTER_EVIDENCE_FOUND", "NOT_RUN");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ResearchRepairExecutionService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    static void validateReport(Object value) {
        JsonNode report = new ObjectMapper().valueToTree(value);
        if (!report.isObject() || report.size() != 3 || !report.has("queries") || !report.has("strategy") || !report.has("counter_checks")
                || !STRATEGIES.contains(report.path("strategy").asText())) throw invalid();
        JsonNode queries = report.get("queries"), checks = report.get("counter_checks");
        if (!queries.isArray() || queries.size() > 32 || !checks.isArray() || checks.size() > 12) throw invalid();
        for (JsonNode q : queries) {
            text(q, 200);
            if (q.asText().contains("::") || q.asText().chars().anyMatch(c -> c < 32)) throw invalid();
        }
        for (JsonNode check : checks) {
            if (!check.isObject() || check.size() != 6) throw invalid();
            text(check.get("cell_key"), 160); text(check.get("original_claim"), 16384); text(check.get("claim_text"), 2000);
            if (!VERDICTS.contains(check.path("verdict").asText()) || !check.path("queries").isArray()
                    || check.get("queries").size() > 1 || !check.path("refuting_evidence_keys").isArray()
                    || check.get("refuting_evidence_keys").size() > 6) throw invalid();
            for (JsonNode q : check.get("queries")) {
                boolean found = false;
                for (JsonNode executed : queries) if (q.equals(executed)) found = true;
                if (!found) throw invalid();
            }
            boolean refuted = "REFUTED".equals(check.get("verdict").asText());
            if (refuted == check.get("refuting_evidence_keys").isEmpty()) throw invalid();
            for (JsonNode ref : check.get("refuting_evidence_keys")) text(ref, 64);
            if (!"NOT_RUN".equals(check.get("verdict").asText()) && check.get("queries").isEmpty()) throw invalid();
        }
    }

    private static void text(JsonNode node, int max) {
        if (node == null || !node.isTextual() || node.asText().isBlank() || node.asText().length() > max) throw invalid();
    }

    private static BusinessException invalid() {
        return new BusinessException("RESEARCH_REPAIR_EXECUTION_INVALID", "查询历史或反证结果不符合约定");
    }

    JsonNode report(ResearchAgentCompletionEnvelope envelope) {
        Object raw = envelope.extractionDiagnostics() == null ? null : envelope.extractionDiagnostics().get("execution_report");
        return raw == null ? mapper.createObjectNode() : mapper.valueToTree(raw);
    }

    Set<String> rejectedCandidates(ResearchAgentCompletionCommitter.TaskRow task, ResearchAgentCompletionEnvelope envelope) {
        return rejectedCandidates(task.executionContextJson(), envelope);
    }

    /** 只给实际执行反证且未被反驳的事实资格，不将代表值的核验结果扩展到互补事实。 */
    List<String> factRejectionReasons(String executionContextJson, ResearchAgentCompletionEnvelope envelope, String cell, String claim) {
        if (!read(executionContextJson).path("query_policy").path("counter_required").asBoolean()) return List.of();
        List<JsonNode> checks = new ArrayList<>();
        report(envelope).path("counter_checks").forEach(check -> {
            if (cell.equals(check.path("cell_key").asText()) && claim.equals(check.path("original_claim").asText())) checks.add(check);
        });
        if (checks.isEmpty() || checks.stream().anyMatch(check -> "NOT_RUN".equals(check.path("verdict").asText())))
            return List.of("FACT_COUNTER_CHECK_INCOMPLETE");
        return checks.stream().anyMatch(check -> "REFUTED".equals(check.path("verdict").asText()))
                ? List.of("FACT_COUNTER_CHECK_REFUTED") : List.of();
    }

    Set<String> rejectedCandidates(String executionContextJson, ResearchAgentCompletionEnvelope envelope) {
        JsonNode policy = read(executionContextJson).path("query_policy");
        if (!policy.path("counter_required").asBoolean()) return Set.of();
        Set<String> rejected = new java.util.HashSet<>();
        boolean incompleteConflict = "EVIDENCE_CONFLICT".equals(policy.path("repair_target").path("repair_cause").asText())
                && java.util.stream.StreamSupport.stream(report(envelope).path("counter_checks").spliterator(), false)
                .anyMatch(check -> "NOT_RUN".equals(check.path("verdict").asText()));
        if ("EVIDENCE_CONFLICT".equals(policy.path("repair_target").path("repair_cause").asText())) {
            for (JsonNode claim : policy.path("repair_target").path("claims")) {
                boolean found = java.util.stream.StreamSupport.stream(report(envelope).path("counter_checks").spliterator(), false)
                        .anyMatch(check -> claim.path("claim_text").asText().equals(check.path("original_claim").asText()));
                if (!found) incompleteConflict = true;
            }
        }
        for (var c : envelope.candidates()) {
            List<JsonNode> checks = new ArrayList<>();
            report(envelope).path("counter_checks").forEach(check -> {
                if (c.cellKey().equals(check.path("cell_key").asText()) && c.candidateValue().equals(check.path("original_claim").asText())) checks.add(check);
            });
            if (incompleteConflict || checks.isEmpty() || checks.stream().anyMatch(check -> Set.of("NOT_RUN", "REFUTED").contains(check.path("verdict").asText()))) rejected.add(c.candidateKey());
        }
        return Set.copyOf(rejected);
    }

    void persist(ResearchAgentCompletionCommitter.TaskRow task, ResearchAgentCompletionEnvelope envelope) {
        JsonNode report = report(envelope);
        if (report.isEmpty()) return;
        validateReport(report);
        JsonNode bindings = read(task.targetBindingsJson());
        JsonNode policy = read(task.executionContextJson()).path("query_policy");
        Set<String> cells = new java.util.HashSet<>();
        bindings.forEach(binding -> cells.add(binding.path("cell_id").asText(binding.path("cell_key").asText())));
        // 目标列表采用服务端快照，不信任 Worker 指定的任意单元格。
        if (cells.isEmpty()) throw invalid();
        for (JsonNode check : report.path("counter_checks")) {
            if (!cells.contains(check.path("cell_key").asText())) throw invalid();
            boolean knownClaim = envelope.candidates().stream().anyMatch(c -> c.cellKey().equals(check.path("cell_key").asText())
                    && c.candidateValue().equals(check.path("original_claim").asText()));
            for (JsonNode claim : policy.path("repair_target").path("claims")) {
                if (claim.path("claim_text").asText().equals(check.path("original_claim").asText())
                        && policy.path("repair_target").path("cell_key").asText().equals(check.path("cell_key").asText())) knownClaim = true;
            }
            if (!knownClaim) throw invalid();
            if (!"NOT_RUN".equals(check.path("verdict").asText()) && (envelope.budgetUsage().get("llm_calls") < 2
                    || envelope.budgetUsage().get("search_calls") < 1 || envelope.budgetUsage().get("read_calls") < 1)) throw invalid();
            for (JsonNode ref : check.get("refuting_evidence_keys")) {
                if (envelope.evidence().stream().noneMatch(e -> e.evidenceKey().equals(ref.asText()) && "CONFLICTS".equals(e.relationType())
                        && e.claimText().equals(check.get("claim_text").asText()))) throw invalid();
            }
            jdbc.update("""
                    insert into research_counter_check(id, research_run_id, task_id, cell_key, original_claim,
                        claim_text, verdict, counter_query_count, refuting_evidence_keys_json)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), task.runId(), task.id(), check.get("cell_key").asText(), check.get("original_claim").asText(),
                    check.get("claim_text").asText(), check.get("verdict").asText(), check.get("queries").size(),
                    Json.write(mapper, check.get("refuting_evidence_keys")));
        }
        for (String cell : cells) for (JsonNode q : report.path("queries")) jdbc.update("""
                insert into research_task_query(id, research_run_id, task_id, cell_key, query_text, strategy)
                values (?, ?, ?, ?, ?, ?)
                """, Ids.newId(), task.runId(), task.id(), cell, q.asText(), report.get("strategy").asText());
        if (policy.has("repair_target")) jdbc.update("""
                update research_agent_replan_audit set repair_cause = ?, strategy = ?,
                    query_count = query_count + ? where research_run_id = ? and plan_revision_to = ?
                """, policy.path("repair_target").path("repair_cause").asText(), report.get("strategy").asText(),
                report.get("queries").size(), task.runId(), task.planRevision());
    }

    boolean persistedCounterSupports(String taskId, String cellKey, String claim) {
        String context = jdbc.queryForObject("select execution_context_json from research_agent_task where id = ?", String.class, taskId);
        if (!read(context).path("query_policy").path("counter_required").asBoolean()) return true;
        List<String> checks = jdbc.queryForList("select verdict from research_counter_check where task_id = ? and cell_key = ? and original_claim = ?",
                String.class, taskId, cellKey, claim);
        return !checks.isEmpty() && checks.stream().noneMatch(v -> Set.of("REFUTED", "NOT_RUN").contains(v));
    }

    static String appendCounterAudit(JdbcTemplate jdbc, String runId, String markdown) {
        List<String[]> checks = jdbc.query("select claim_text, verdict, counter_query_count from research_counter_check where research_run_id = ? order by checked_at, id",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2), String.valueOf(rs.getInt(3))}, runId);
        if (checks.isEmpty()) return markdown;
        StringBuilder result = new StringBuilder(markdown).append("\n\n## 反证记录\n\n| 待验证的原子事实 | 判定 | 实际查询数 |\n|---|---|---|\n");
        for (String[] check : checks) result.append('|').append(check[0].replace("|", "\\|").replace("\n", " ").replace("\r", " "))
                .append('|').append(check[1]).append('|').append(check[2]).append("|\n");
        return result.toString();
    }

    private JsonNode read(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return node.isTextual() ? mapper.readTree(node.asText()) : node;
        } catch (Exception e) { throw invalid(); }
    }

    private void requireRun(String workspaceId, String runId) {
        Integer count = jdbc.queryForObject("select count(*) from research_run where id = ? and workspace_id = ?", Integer.class, runId, workspaceId);
        if (count == null || count != 1) throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究任务不存在");
    }

    public List<Map<String, Object>> counterChecks(String workspaceId, String runId) {
        requireRun(workspaceId, runId);
        return jdbc.query("""
                select cc.*, coalesce(rr.source_title, cc.cell_key) as entity_label,
                    coalesce(col.label, rc.column_key) as column_label
                from research_counter_check cc
                join research_cell rc on rc.research_run_id = cc.research_run_id and rc.cell_key = cc.cell_key
                left join research_row rr on rr.id = rc.research_row_id
                left join research_column col on col.research_run_id = rc.research_run_id and col.column_key = rc.column_key
                where cc.research_run_id = ? order by cc.checked_at, cc.id
                """, (rs, n) -> Map.<String, Object>of("cell_key", rs.getString("cell_key"), "entity_label", rs.getString("entity_label"),
                "column_label", rs.getString("column_label"), "claim_text", rs.getString("claim_text"), "verdict", rs.getString("verdict"),
                "counter_query_count", rs.getInt("counter_query_count"), "refuting_evidence_keys", read(rs.getString("refuting_evidence_keys_json")),
                "checked_at", rs.getTimestamp("checked_at").toInstant().toString()), runId);
    }

    public List<Map<String, Object>> replans(String workspaceId, String runId) {
        requireRun(workspaceId, runId);
        return jdbc.query("select * from research_agent_replan_audit where research_run_id = ? order by plan_revision_to", (rs, n) -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("plan_revision_from", rs.getInt("plan_revision_from")); row.put("plan_revision_to", rs.getInt("plan_revision_to"));
            row.put("deviation_type", rs.getString("deviation_type"));
            row.put("repair_cause", rs.getString("repair_cause") == null ? rs.getString("deviation_type") : rs.getString("repair_cause"));
            row.put("observed_facts", rs.getString("observed_facts")); row.put("affected_cells", read(rs.getString("affected_cells_json")));
            row.put("strategy", rs.getString("strategy") == null ? "COUNTER_EVIDENCE" : rs.getString("strategy"));
            row.put("query_count", rs.getInt("query_count")); row.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
            return row;
        }, runId);
    }
}
