package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** 成文只提交缺项描述，补查由持有 Run 锁的协调器限次派发。 */
final class ResearchReportFeedbackService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    ResearchReportFeedbackService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    void record(ResearchAgentCompletionCommitter.TaskRow task, Map<String,Object> result) {
        var coverage = new ResearchRequirementCoverageService(jdbc, mapper);
        if (!coverage.fullCompletion(task.runId()) || !coverage.enforces(task.runId())) return;
        var root = mapper.valueToTree(result);
        var frozen = new ResearchDiscoveryBudget(jdbc,mapper).read(task.executionContextJson()).path("query_policy").path("synthesis_input").path("requirement_coverage").path("requirements");
        java.util.Set<String> recorded = new java.util.HashSet<>();
        for (var item : root.path("narrative").path("report_coverage").path("items")) {
            if (!"MISSING_FACTS".equals(item.path("gap_type").asText())) continue;
            String requirement = item.path("requirement_id").asText();
            com.fasterxml.jackson.databind.JsonNode requested = null;
            for (var candidate : frozen) if (requirement.equals(candidate.path("requirement_id").asText())) requested = candidate;
            if (requested == null) continue;
            List<com.fasterxml.jackson.databind.JsonNode> targets = new ArrayList<>();
            if ("CELL".equals(requested.path("scope_type").asText())) targets.add(requested);
            else if ("COMPARISON".equals(requested.path("completion_mode").asText())) {
                java.util.Set<String> entities = new java.util.HashSet<>(); requested.path("scope").path("entity_keys").forEach(entity -> entities.add(entity.asText()));
                String column = requested.path("scope").path("column_key").asText();
                for (var candidate : frozen) {
                    String[] position = candidate.path("cell_key").asText().split(":",2);
                    if ("CELL".equals(candidate.path("scope_type").asText()) && "EXPLICIT".equals(candidate.path("origin").asText())
                            && candidate.path("question_quote").equals(requested.path("question_quote")) && position.length == 2
                            && entities.contains(position[0]) && column.equals(position[1])) targets.add(candidate);
                }
            }
            for (var target : targets) {
                if (coverage.answerPolicy(task.runId()) && !target.path("mandatory").asBoolean(true)) continue;
                String identity = target.path("requirement_id").asText();
                if (!recorded.add(identity)) continue;
                int count = jdbc.queryForObject("select count(*) from research_report_feedback where research_run_id = ? and requirement_id = ?",Integer.class,task.runId(),identity);
                if (coverage.answerPolicy(task.runId())) {
                    String lineage = target.path("lineage_key").asText();
                    if (!lineage.isBlank()) {
                        count = jdbc.queryForObject("""
                                select count(*) from research_report_feedback feedback
                                join research_requirement_revision revision on revision.requirement_id = feedback.requirement_id
                                where feedback.research_run_id = ? and revision.lineage_key = ?
                                """,Integer.class,task.runId(),lineage);
                        int duplicate = jdbc.queryForObject("""
                                select count(*) from research_report_feedback feedback
                                join research_requirement_revision revision on revision.requirement_id = feedback.requirement_id
                                where feedback.research_run_id = ? and revision.lineage_key = ? and lower(trim(feedback.missing_detail)) = lower(trim(?))
                                """,Integer.class,task.runId(),lineage,item.path("missing_detail").asText());
                        if (duplicate > 0) continue;
                    }
                }
                int total = jdbc.queryForObject("select count(*) from research_report_feedback where research_run_id = ?",Integer.class,task.runId());
                if (count >= 2 || total >= 12) continue;
                jdbc.update("insert into research_report_feedback(id,research_run_id,requirement_id,cell_key,source_task_id,round_no,missing_detail) values (?,?,?,?,?,?,?)",
                        Ids.newId(),task.runId(),identity,target.path("cell_key").asText(),task.id(),count+1,item.path("missing_detail").asText());
            }
        }
    }

    ResearchAgentTaskCoordinatorService.CoordinatorReceipt advance(String run, int checkpoint, int wave, ResearchAgentTaskCoordinatorService coordinator) {
        var coverage = new ResearchRequirementCoverageService(jdbc,mapper);
        if (!coverage.fullCompletion(run) || !coverage.enforces(run)) return null;
        // 未产生新合格事实的补查不会恢复旧的完成结论，也不会无限追加同一轮任务。
        jdbc.update("""
                update research_report_feedback set feedback_status = 'EXHAUSTED', updated_at = current_timestamp
                where research_run_id = ? and feedback_status = 'TASKIZED' and exists (
                    select 1 from research_agent_task task where task.id = repair_task_id and task.status in ('SUBMITTED','FAILED','CANCELLED'))
                """, run);
        List<String> cells = jdbc.queryForList("select distinct cell_key from research_report_feedback where research_run_id = ? and feedback_status = 'OPEN' order by cell_key limit 3", String.class, run);
        if (cells.isEmpty()) return null;
        if (!new ResearchDiscoveryBudget(jdbc, mapper).allows(run, ResearchAgentTaskCoordinatorService.deepCellBudget(1), cells.size(), "COUNTERFACTUAL")) {
            jdbc.update("update research_report_feedback set feedback_status = 'BUDGET_EXHAUSTED', updated_at = current_timestamp where research_run_id = ? and feedback_status = 'OPEN'", run);
            return null;
        }
        List<ResearchAgentTaskCoordinatorService.CounterfactualTarget> targets = new ArrayList<>();
        for (String cell : cells) {
            int changed = jdbc.update("""
                    update research_cell set cell_status = 'GAP', cell_version = cell_version + 1,
                        last_verifier_decision = 'REPORT_FACTS_MISSING', updated_at = current_timestamp
                    where research_run_id = ? and cell_key = ? and active_task_id is null and cell_status <> 'FROZEN'
                    """, run, cell);
            if (changed == 0) {
                jdbc.update("update research_report_feedback set feedback_status = 'SCOPE_UNAVAILABLE', updated_at = current_timestamp where research_run_id = ? and cell_key = ? and feedback_status = 'OPEN'", run, cell);
                continue;
            }
            String signature = new ResearchAgentCompletionCanonicalizer().domainSeparatedDigest("research-report-feedback.v1", details(run, cell));
            targets.add(new ResearchAgentTaskCoordinatorService.CounterfactualTarget(cell, signature, List.of()));
        }
        if (targets.isEmpty()) return null;
        var receipt = coordinator.planCounterfactualRepairs(new ResearchAgentTaskCoordinatorService.CounterfactualRepairCommand(run, checkpoint, wave + 1, targets));
        for (var target : targets) {
            String task = jdbc.query("select id from research_agent_task where research_run_id = ? and wave_no = ? and role = 'COUNTERFACTUAL' and target_cells_json like ? order by created_at desc limit 1",
                    rs -> rs.next() ? rs.getString(1) : null, run, wave + 1, "%\"" + target.cellKey() + "\"%");
            jdbc.update("update research_report_feedback set feedback_status = ?, repair_task_id = ?, updated_at = current_timestamp where research_run_id = ? and cell_key = ? and feedback_status = 'OPEN'",
                    task == null ? "BUDGET_EXHAUSTED" : "TASKIZED", task, run, target.cellKey());
        }
        jdbc.update("update research_agent_synthesis_candidate set status = 'STALE' where research_run_id = ? and status = 'VALIDATED'", run);
        return receipt;
    }
    List<Map<String,Object>> details(String run, String cell) {
        return jdbc.query("select id,requirement_id,missing_detail,feedback_status from research_report_feedback where research_run_id = ? and (? = '' or cell_key = ?) order by created_at,id",
                (rs,n) -> Map.<String,Object>of("id",rs.getString(1),"requirement_id",rs.getString(2),"missing_detail",rs.getString(3),"status",rs.getString(4)), run,cell,cell);
    }
}
