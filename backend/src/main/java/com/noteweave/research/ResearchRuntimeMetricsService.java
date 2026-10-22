package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Json;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** 指标与完成回执同事务保存，前端只展示这些权威记录。 */
final class ResearchRuntimeMetricsService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    ResearchRuntimeMetricsService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    void record(ResearchAgentCompletionCommitter.TaskRow task, ResearchAgentCompletionEnvelope envelope, String completion) {
        var coverage = new ResearchRequirementCoverageService(jdbc, mapper);
        if (!coverage.fullCompletion(task.runId())) return;
        var diagnostics = mapper.valueToTree(envelope.extractionDiagnostics() == null ? Map.of() : envelope.extractionDiagnostics());
        JsonNode runtime = diagnostics.path("runtime_metrics");
        if (runtime.isMissingNode() && "ROLE_RESULT".equals(envelope.terminationReason())) {
            var payloads = jdbc.queryForList("select payload_json from research_agent_role_result where completion_id = ?",String.class,completion);
            if (!payloads.isEmpty()) runtime = read(payloads.get(0)).path("runtime_metrics");
        }
        if (!runtime.isMissingNode()) validate(runtime);
        List<String> fingerprints = new ArrayList<>(); runtime.path("document_fingerprints").forEach(item -> fingerprints.add(item.asText()));
        Set<String> previous = new HashSet<>();
        for (String raw : jdbc.queryForList("select document_fingerprints_json from research_runtime_metric where research_run_id = ?", String.class, task.runId()))
            read(raw).forEach(value -> previous.add(value.asText()));
        int duplicates = 0;
        for (String fingerprint : fingerprints) if (!previous.add(fingerprint)) duplicates++;
        int qualified = jdbc.queryForObject("select count(*) from research_requirement_fact_support where completion_id = ? and qualification_status = 'QUALIFIED'", Integer.class, completion);
        Map<String,Integer> rejections = new LinkedHashMap<>();
        diagnostics.path("rejection_counts").fields().forEachRemaining(entry -> rejections.put(entry.getKey(),entry.getValue().asInt()));
        for (String raw : jdbc.queryForList("select reason_codes_json from research_requirement_fact_support where completion_id = ? and qualification_status <> 'QUALIFIED'", String.class, completion))
            read(raw).forEach(value -> rejections.merge(value.asText(),1,Integer::sum));
        jdbc.update("""
                insert into research_runtime_metric(completion_id,research_run_id,task_id,role,query_count,read_page_count,llm_calls,
                    provider_call_count,input_tokens,output_tokens,duration_ms,extracted_fact_count,qualified_support_count,rejection_counts_json,document_fingerprints_json,duplicate_document_count)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, completion,task.runId(),task.id(),task.role(),diagnostics.path("execution_report").path("queries").size(),
                envelope.telemetry().getOrDefault("windows",0L),envelope.budgetUsage().getOrDefault("llm_calls",0L),
                nullable(runtime,"provider_call_count"),nullable(runtime,"input_tokens"),nullable(runtime,"output_tokens"),nullable(runtime,"duration_ms"),
                diagnostics.path("claim_clusters").size(),qualified,Json.write(mapper,rejections),Json.write(mapper,fingerprints),duplicates);
        JsonNode progress = mapper.valueToTree(coverage.readModel(task.runId()));
        for (JsonNode binding : read(task.targetBindingsJson())) {
            String cell = binding.path("cell_id").asText();
            if (cell.isBlank()) continue;
            var facts = coverage.facts(task.runId(),cell,task.planRevision(),task.entitySetVersion());
            int count = (int) facts.stream().map(fact -> fact.get("equivalence_group_id")).distinct().count();
            int satisfied = (int) java.util.stream.StreamSupport.stream(progress.path("requirements").spliterator(),false)
                    .filter(req -> cell.equals(req.path("cell_key").asText()) && "SATISFIED".equals(req.path("status").asText())).count();
            var last = jdbc.query("select eligible_fact_count,satisfied_requirement_count,conflict_open from research_cell_gain where research_run_id = ? and cell_key = ? and plan_revision = ? and entity_set_version = ? order by event_seq desc limit 1",
                    (rs,n) -> new int[]{rs.getInt(1),rs.getInt(2),rs.getBoolean(3) ? 1 : 0},task.runId(),cell,task.planRevision(),task.entitySetVersion());
            int[] before = last.isEmpty() ? new int[]{0,0,0} : last.get(0);
            boolean conflict = jdbc.queryForObject("select count(*) > 0 from research_cell where research_run_id = ? and cell_key = ? and cell_status in ('CONFLICTED','CONFLICT_EXHAUSTED')",Boolean.class,task.runId(),cell);
            jdbc.update("insert into research_cell_gain(completion_id,research_run_id,cell_key,plan_revision,entity_set_version,eligible_fact_count,satisfied_requirement_count,new_fact_count,new_requirement_count,conflict_open,resolved_conflict_count) values (?,?,?,?,?,?,?,?,?,?,?)",
                    completion,task.runId(),cell,task.planRevision(),task.entitySetVersion(),count,satisfied,Math.max(0,count-before[0]),Math.max(0,satisfied-before[1]),conflict,before[2] == 1 && !conflict ? 1 : 0);
        }
        jdbc.update("update research_runtime_metric set unresolved_conflict_count = (select count(*) from research_cell where research_run_id = ? and cell_status in ('CONFLICTED','CONFLICT_EXHAUSTED')), resolved_conflict_count = (select coalesce(sum(resolved_conflict_count),0) from research_cell_gain where completion_id = ?) where completion_id = ?",task.runId(),completion,completion);
    }
    Map<String,Object> view(String run) {
        Map<String,Object> result = new LinkedHashMap<>();
        var rounds = jdbc.query("select completion_id,role,query_count,read_page_count,llm_calls,provider_call_count,input_tokens,output_tokens,duration_ms,extracted_fact_count,qualified_support_count,rejection_counts_json,duplicate_document_count,document_fingerprints_json,resolved_conflict_count from research_runtime_metric where research_run_id = ? order by event_seq", (rs,n) -> {
            Map<String,Object> row = new LinkedHashMap<>();
            for (String name : List.of("completion_id","role","query_count","read_page_count","llm_calls","provider_call_count","input_tokens","output_tokens","duration_ms","extracted_fact_count","qualified_support_count","duplicate_document_count","resolved_conflict_count")) row.put(name,rs.getObject(name));
            row.put("rejection_counts", mapper.convertValue(read(rs.getString("rejection_counts_json")),Object.class));
            row.put("document_count",read(rs.getString("document_fingerprints_json")).size()); return row;
        },run);
        result.put("rounds",rounds);
        var gains = jdbc.query("select cell_key,completion_id,new_fact_count,new_requirement_count from research_cell_gain where research_run_id = ? order by event_seq",
                (rs,n) -> Map.<String,Object>of("cell_key",rs.getString(1),"completion_id",rs.getString(2),"new_fact_count",rs.getInt(3),"new_requirement_count",rs.getInt(4)),run);
        result.put("cell_gains",gains);
        long gained = gains.stream().mapToLong(row -> ((Number)row.get("new_requirement_count")).longValue()).sum();
        long calls = rounds.stream().mapToLong(row -> ((Number)row.get("llm_calls")).longValue()).sum();
        result.put("unresolved_conflicts", jdbc.queryForObject("select count(*) from research_cell where research_run_id = ? and cell_status in ('CONFLICTED','CONFLICT_EXHAUSTED')",Integer.class,run));
        result.put("stop_reason",jdbc.query("select stop_reason from research_discovery_loop where research_run_id = ?",rs -> rs.next() ? rs.getString(1) : null,run));
        result.put("report_feedback",new ResearchReportFeedbackService(jdbc,mapper).details(run,""));
        long unsubmittedCalls = jdbc.queryForObject("""
                select count(*) from research_synthesis_call_permit permit join research_agent_task task on task.id = permit.task_id
                where task.research_run_id = ? and task.status in ('FAILED','CANCELLED')
                  and not exists (select 1 from research_agent_completion completion where completion.research_agent_task_id = task.id)
                """,Long.class,run);
        result.put("unsubmitted_synthesis_authorizations",unsubmittedCalls);
        result.put("llm_calls_per_new_requirement",gained == 0 ? null : (double)(calls+unsubmittedCalls)/gained);
        return result;
    }
    static void validate(JsonNode node) {
        Set<String> fields = new HashSet<>(); node.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("schema_version","input_tokens","output_tokens","duration_ms","document_fingerprints","provider_call_count"))
                || !"research-runtime-metrics.v1".equals(node.path("schema_version").asText())
                || !node.path("document_fingerprints").isArray() || node.path("document_fingerprints").size() > 64) throw invalid();
        for (String field : List.of("input_tokens","output_tokens","duration_ms","provider_call_count")) if (!node.path(field).isNull()
                && (!node.path(field).isIntegralNumber() || !node.path(field).canConvertToLong() || node.path(field).asLong() < 0 || node.path(field).asLong() > 1_000_000_000L)) throw invalid();
        for (JsonNode value : node.path("document_fingerprints")) if (!value.isTextual() || !value.asText().matches("[0-9a-f]{64}")) throw invalid();
    }
    private Long nullable(JsonNode node,String field) { return node.path(field).isIntegralNumber() ? node.path(field).asLong() : null; }
    private JsonNode read(String raw) { try { var node = mapper.readTree(raw); return node.isTextual() ? mapper.readTree(node.asText()) : node; } catch (Exception error) { throw invalid(); } }
    private static BusinessException invalid() { return new BusinessException("RESEARCH_RUNTIME_METRIC_INVALID","运行指标格式不符合约定"); }
}
