package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** 报告覆盖独立于研究事实覆盖，未明确作答时保留事实并按部分结果交付。 */
final class ResearchReportCoverageService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    ResearchReportCoverageService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    void validate(Map<String, Object> input, Map<String, Object> result, long chargedCalls) {
        JsonNode frozen = mapper.valueToTree(input), narrative = mapper.valueToTree(result.get("narrative"));
        JsonNode coverage = narrative.path("report_coverage");
        if (!"research-report-coverage.v1".equals(coverage.path("schema_version").asText()) || !coverage.path("items").isArray()
                || !coverage.path("model_calls").isIntegralNumber() || !coverage.path("model_calls").canConvertToInt() || !coverage.path("complete").isBoolean()) throw invalid("报告缺少需求覆盖核验");
        int calls = coverage.path("model_calls").asInt();
        if (calls < 0 || calls + narrative.path("statement_support").path("model_calls").asLong() > chargedCalls) throw invalid("报告需求核验调用与预算不一致");
        Map<String, JsonNode> requirements = new LinkedHashMap<>(), statements = new LinkedHashMap<>(), facts = new LinkedHashMap<>();
        frozen.path("requirement_coverage").path("requirements").forEach(req -> requirements.put(req.path("requirement_id").asText(), req));
        narrative.path("statement_support").path("checks").forEach(statement -> statements.put(statement.path("statement_id").asText(), statement));
        Map<String, String> cells = new LinkedHashMap<>();
        for (JsonNode cell : frozen.path("cells")) for (JsonNode fact : cell.path("facts")) {
            facts.put(fact.path("fact_id").asText(), fact); cells.put(fact.path("fact_id").asText(), cell.path("cell_key").asText());
        }
        Set<String> observed = new HashSet<>(); boolean complete = true;
        for (JsonNode item : coverage.path("items")) {
            Set<String> fields = new HashSet<>(); item.fieldNames().forEachRemaining(fields::add);
            String id = item.path("requirement_id").asText(), status = item.path("status").asText();
            boolean feedback = frozen.path("report_feedback_version").asInt() == 1;
            Set<String> expectedFields = feedback ? Set.of("requirement_id", "status", "statement_ids", "gap_type", "missing_detail") : Set.of("requirement_id", "status", "statement_ids");
            if (frozen.path(ResearchAnswerPolicy.FLAG).asInt() == 1) {
                expectedFields = new HashSet<>(expectedFields); expectedFields.add("reason_code");
                if (!Set.of("ANSWER_COMPLETE", "ANSWER_INCOMPLETE", "MODEL_UNCERTAIN", "MODEL_UNAVAILABLE", "BUDGET_EXHAUSTED",
                        "INVALID_MODEL_OUTPUT", "PROVIDER_TIMEOUT", "PROVIDER_CONNECTION_ERROR", "PROVIDER_ERROR").contains(item.path("reason_code").asText()))
                    throw invalid("报告核验缺少明确原因");
                if ("ANSWERED".equals(status) != "ANSWER_COMPLETE".equals(item.path("reason_code").asText()))
                    throw invalid("报告核验状态与原因不一致");
            }
            if (!fields.equals(expectedFields) || !requirements.containsKey(id) || !observed.add(id)
                    || !Set.of("ANSWERED", "PARTIAL", "UNANSWERED", "NOT_CHECKED").contains(status)) throw invalid("报告需求核验范围或格式错误");
            if (feedback && (!Set.of("NONE", "EXPRESSION", "MISSING_FACTS", "NOT_CHECKED").contains(item.path("gap_type").asText())
                    || !item.path("missing_detail").isTextual() || item.path("missing_detail").asText().length() > 2000
                    || ("ANSWERED".equals(status) != "NONE".equals(item.path("gap_type").asText()))
                    || ("NOT_CHECKED".equals(status) != "NOT_CHECKED".equals(item.path("gap_type").asText()))
                    || "MISSING_FACTS".equals(item.path("gap_type").asText()) && (calls == 0 || item.path("missing_detail").asText().isBlank())))
                throw invalid("报告事实缺项反馈不符合约定");
            JsonNode requirement = requirements.get(id); List<String> references = strings(item.path("statement_ids"));
            if (Set.of("ANSWERED", "PARTIAL").contains(status) && (references.isEmpty() || calls == 0)) throw invalid("报告答案缺少独立核验或正文依据");
            Set<String> canonical = new HashSet<>(), entities = new HashSet<>();
            for (String reference : references) {
                JsonNode statement = statements.get(reference);
                if (statement == null || !"SUPPORTED".equals(statement.path("verdict").asText()) || "NO_CLAIM".equals(statement.path("basis").asText())) throw invalid("报告需求引用了未核验语句");
                boolean scoped = false;
                for (String factId : strings(statement.path("fact_ids"))) {
                    if (!facts.containsKey(factId)) continue;
                    if (!"RUN".equals(requirement.path("scope_type").asText()) && !requirement.path("cell_key").asText().equals(cells.get(factId))) continue;
                    if ("RUN".equals(requirement.path("scope_type").asText())) {
                        String[] position = cells.get(factId).split(":",2);
                        JsonNode scope = requirement.path("scope");
                        if (!scope.path("entity_keys").isEmpty() && !strings(scope.path("entity_keys")).contains(position[0])) continue;
                        if (!scope.path("column_key").asText().isBlank() && (position.length < 2 || !scope.path("column_key").asText().equals(position[1]))) continue;
                    }
                    Set<String> aliases = new HashSet<>(); aliases.add(factId);
                    facts.get(factId).path("equivalent_fact_ids").forEach(alias -> aliases.add(alias.asText()));
                    List<String> eligible = requirement.has("supporting_fact_ids") ? strings(requirement.path("supporting_fact_ids")) : List.of();
                    if (!eligible.isEmpty() && eligible.stream().noneMatch(aliases::contains)) continue;
                    canonical.add(factId); entities.add(cells.get(factId).split(":", 2)[0]); scoped = true;
                }
                if (!scoped) throw invalid("报告语句与需求对象或合格事实不对应");
            }
            if ("ANSWERED".equals(status)) {
                if (!"SATISFIED".equals(requirement.path("status").asText())) throw invalid("研究需求未满足，报告不能声明完整回答");
                int count = Set.of("DISTINCT_ENTITIES","COMPARISON").contains(requirement.path("completion_mode").asText()) ? entities.size() : canonical.size();
                if (count < requirement.path("minimum_count").asInt(1)) throw invalid("报告答案未达到需求数量");
            } else if (frozen.path(ResearchAnswerPolicy.FLAG).asInt() != 1 || requirement.path("mandatory").asBoolean(true)) complete = false;
        }
        if (!observed.equals(requirements.keySet()) || complete != coverage.path("complete").asBoolean()) throw invalid("报告需求核验缺项或总状态不一致");
        if (frozen.path(ResearchAnswerPolicy.FLAG).asInt() == 1) validateAnswers(narrative, coverage, statements);
    }

    private void validateAnswers(JsonNode narrative, JsonNode coverage, Map<String,JsonNode> statements) {
        if (!narrative.path("answers").isArray() || narrative.path("answers").size() != coverage.path("items").size())
            throw invalid("报告必须逐项提供答案记录");
        Map<String,JsonNode> items = new LinkedHashMap<>();
        coverage.path("items").forEach(item -> items.put(item.path("requirement_id").asText(), item));
        Set<String> seen = new HashSet<>();
        for (JsonNode answer : narrative.path("answers")) {
            String id = answer.path("requirement_id").asText();
            JsonNode item = items.get(id);
            Set<String> fields = new HashSet<>(); answer.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("requirement_id","text","fact_ids","statement_ids","status","missing_conditions","reason_code"))
                    || item == null || !seen.add(id) || !answer.path("statement_ids").equals(item.path("statement_ids"))
                    || !answer.path("status").equals(item.path("status")) || !answer.path("reason_code").equals(item.path("reason_code")))
                throw invalid("逐项答案与覆盖回执不一致");
            List<String> refs = strings(answer.path("statement_ids")); Set<String> facts = new HashSet<>();
            List<String> texts = new ArrayList<>();
            for (String ref : refs) { texts.add(statements.get(ref).path("text").asText()); facts.addAll(strings(statements.get(ref).path("fact_ids"))); }
            if (!answer.path("text").isTextual() || !answer.path("text").asText().equals(String.join(" ",texts))
                    || !facts.equals(new HashSet<>(strings(answer.path("fact_ids"))))
                    || !answer.path("missing_conditions").isArray() || answer.path("missing_conditions").size() > 1)
                throw invalid("逐项答案必须由最终已核验正文和事实组成");
            List<String> missing = new ArrayList<>();
            if (!item.path("missing_detail").asText().isEmpty()) missing.add(item.path("missing_detail").asText());
            if (!mapper.valueToTree(missing).equals(answer.path("missing_conditions"))) throw invalid("逐项答案缺项条件与回执不一致");
        }
    }

    JsonNode latest(String run) {
        List<String> values = jdbc.queryForList("""
                select result.payload_json from research_agent_synthesis_candidate candidate
                join research_agent_role_result result on result.id = candidate.role_result_id
                where candidate.research_run_id = ? and candidate.status in ('VALIDATED','PROMOTED')
                order by candidate.created_at desc, candidate.id desc limit 1
                """, String.class, run);
        if (values.isEmpty()) return mapper.createObjectNode();
        try { JsonNode root = mapper.readTree(values.get(0)); if (root.isTextual()) root = mapper.readTree(root.asText()); return root.path("narrative").path("report_coverage"); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw invalid("已保存的报告需求覆盖无法解析"); }
    }

    Map<String, Object> publicView(String run) {
        JsonNode coverage = latest(run);
        if (coverage.isEmpty()) return Map.of();
        long answered = java.util.stream.StreamSupport.stream(coverage.path("items").spliterator(), false)
                .filter(item -> "ANSWERED".equals(item.path("status").asText())).count();
        var policy = new ResearchRequirementCoverageService(jdbc,mapper);
        if (policy.answerPolicy(run)) {
            Set<String> mandatory = new HashSet<>();
            for (Object raw : (List<?>) policy.readModel(run).get("requirements")) {
                Map<?,?> req = (Map<?,?>)raw;
                if (!Boolean.FALSE.equals(req.get("mandatory"))) mandatory.add(String.valueOf(req.get("requirement_id")));
            }
            answered = java.util.stream.StreamSupport.stream(coverage.path("items").spliterator(),false)
                    .filter(item -> mandatory.contains(item.path("requirement_id").asText()) && "ANSWERED".equals(item.path("status").asText())).count();
            return Map.of("complete",coverage.path("complete").asBoolean(),"required_count",mandatory.size(),
                    "answered_count",answered,"optional_count",coverage.path("items").size()-mandatory.size(),"items",mapper.convertValue(coverage.path("items"),Object.class));
        }
        return Map.of("complete", coverage.path("complete").asBoolean(), "required_count", coverage.path("items").size(),
                "answered_count", answered, "items", mapper.convertValue(coverage.path("items"), Object.class));
    }

    ResearchAgentRunCompletionGate.CompletionDecision apply(String run, ResearchAgentRunCompletionGate.CompletionDecision decision, boolean requireReport) {
        var coveragePolicy = new ResearchRequirementCoverageService(jdbc,mapper);
        if (decision.terminalState().isInfrastructureFailure() || !coveragePolicy.improvedCompletion(run) || !coveragePolicy.enforces(run)) return decision;
        JsonNode coverage = latest(run);
        if (coverage.isEmpty() && !requireReport) return decision;
        if (coverage.path("complete").asBoolean(false)) return decision;
        List<String> limitations = new ArrayList<>(decision.limitations());
        limitations.add("报告尚未通过全部需求的独立完整性核验，已核验事实仍予保留。");
        List<String> reasons = new ArrayList<>(decision.reasonCodes()); reasons.add("REPORT_REQUIREMENTS_INCOMPLETE");
        var state = decision.terminalState() == ResearchAgentRunCompletionGate.CompletionTerminalState.COMPLETED_VERIFIED
                ? ResearchAgentRunCompletionGate.CompletionTerminalState.COMPLETED_WITH_LIMITATIONS : decision.terminalState();
        return new ResearchAgentRunCompletionGate.CompletionDecision(state, decision.promotableClaims(), decision.unresolvedCells(),
                limitations.stream().distinct().toList(), reasons.stream().distinct().toList());
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) throw invalid("报告覆盖引用列表错误");
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) { if (!item.isTextual() || item.asText().isBlank() || result.contains(item.asText())) throw invalid("报告覆盖引用为空或重复"); result.add(item.asText()); }
        return result;
    }
    private static BusinessException invalid(String text) { return new BusinessException("RESEARCH_REPORT_COVERAGE_INVALID", text); }
}
