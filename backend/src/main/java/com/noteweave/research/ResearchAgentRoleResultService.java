package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Validates feature-gated role results before any promotion or repair advancement. */
@Service
class ResearchAgentRoleResultService {
    private static final Set<String> AUDIT_SCHEMAS = Set.of("research-evidence-audit-result.v1", "research-evidence-audit-result.v2");
    private static final Set<String> SYNTHESIS_SCHEMAS = Set.of(
            "research-synthesis-candidate.v1", "research-synthesis-candidate.v2");
    private static final String DIGEST_DOMAIN = "research-agent-role-result.v1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentCompletionCanonicalizer canonicalizer;
    private final ResearchAgentRoleCapabilityRegistry capabilities;
    private final ResearchDiscoveryProposalService discoveryProposalService;
    private final ResearchAgentSynthesisValidationService synthesisValidationService;
    private final ResearchPlanningService planningService;

    ResearchAgentRoleResultService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentCompletionCanonicalizer canonicalizer,
            ResearchAgentRoleCapabilityRegistry capabilities,
            ResearchDiscoveryProposalService discoveryProposalService,
            ResearchAgentSynthesisValidationService synthesisValidationService,
            ResearchPlanningService planningService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
        this.capabilities = capabilities;
        this.discoveryProposalService = discoveryProposalService;
        this.synthesisValidationService = synthesisValidationService;
        this.planningService = planningService;
    }

    void validateAndPersist(
            ResearchAgentCompletionCommitter.TaskRow task,
            ResearchAgentCompletionEnvelope envelope,
            String completionId
    ) {
        if (!ResearchAgentRoles.submitsRoleResult(task.role())) return;
        capabilities.requireRegistered(task.role());
        Map<String, Object> result = envelope.roleResult();
        if (result == null || result.isEmpty()) throw invalid("Role completion has no result payload");
        if ("SYNTHESIS".equals(task.role())) result = new ResearchSynthesisUploadService(jdbcTemplate, objectMapper, null).resolve(task, result);
        if ("EVIDENCE_AUDIT".equals(task.role())) {
            validateAudit(task, result);
        } else if ("SYNTHESIS".equals(task.role())) {
            new ResearchSynthesisRecoveryService(jdbcTemplate, objectMapper, null)
                    .requireRegistered(task, result, envelope.budgetUsage().getOrDefault("llm_calls", 0L));
            validateSynthesis(task, result);
            Map<String, Object> input = mapValue(queryPolicy(task).get("synthesis_input"));
            if (objectMapper.valueToTree(input).path("statement_support_version").asInt() == 1)
                new ResearchStatementSupportValidation().validate(objectMapper, input, result, envelope.budgetUsage().getOrDefault("llm_calls", 0L));
            if (objectMapper.valueToTree(input).path("report_coverage_version").asInt() == 1)
                new ResearchReportCoverageService(jdbcTemplate, objectMapper).validate(input, result, envelope.budgetUsage().getOrDefault("llm_calls", 0L));
        }
        if (ResearchAgentRoles.PLANNING.equals(task.role()) && "PLANNED".equals(result.get("status"))) {
            Map<String,Object> planning = mapValue(queryPolicy(task).get("planning_input"));
            if (objectMapper.valueToTree(planning).path("completion_runtime_version").asInt() == 1)
                ResearchPlanningCompleteness.validate(objectMapper,planning,result,envelope.budgetUsage().getOrDefault("llm_calls",0L));
        }
        String resultDigest = canonicalizer.domainSeparatedDigest(DIGEST_DOMAIN, result);
        String inputDigest = text(result.get("input_digest"));
        if (inputDigest.isBlank()) inputDigest = text(result.get("ledger_digest"));
        String status = text(result.get("status"));
        String roleResultId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_agent_role_result(
                    id, research_run_id, research_agent_task_id, completion_id, role,
                    result_schema_version, result_status, input_digest, result_digest, payload_json)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, roleResultId, task.runId(), task.id(), completionId, task.role(),
                text(result.get("result_schema_version")), status, inputDigest, resultDigest,
                canonicalizer.canonicalJsonValue(result));
        if ("EVIDENCE_AUDIT".equals(task.role())) {
            persistAuditDecisions(task, result, roleResultId);
            var clusterService = new ResearchClaimClusterService(jdbcTemplate, objectMapper);
            new ResearchSemanticAuditService(jdbcTemplate, objectMapper, clusterService).apply(task, envelope, roleResultId);
        } else if ("SYNTHESIS".equals(task.role())) {
            jdbcTemplate.update("""
                    insert into research_agent_synthesis_candidate(
                        id, research_run_id, task_id, role_result_id, ledger_digest,
                        audit_digest, markdown, claims_json, limitations_json, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'VALIDATED')
                    """, Ids.newId(), task.runId(), task.id(), roleResultId,
                    text(result.get("ledger_digest")), text(result.get("audit_digest")),
                    text(result.get("markdown")), Json.write(objectMapper, result.get("claims")),
                    Json.write(objectMapper, result.get("limitations")));
            new ResearchReportFeedbackService(jdbcTemplate, objectMapper).record(task, result);
        } else if (ResearchAgentRoles.PLANNING.equals(task.role())) {
            planningService.applyResult(task, result);
        } else {
            if ("research-discovery-proposal.v2".equals(text(result.get("result_schema_version")))) {
                if (!(result.get("queries") instanceof List<?> queries)
                        || envelope.budgetUsage().getOrDefault("search_calls", 0L) < queries.size())
                    throw invalid("宽搜索检索次数与预算结算不一致");
            }
            discoveryProposalService.validateAndPersist(task, result, roleResultId);
        }
    }

    private void validateAudit(ResearchAgentCompletionCommitter.TaskRow task, Map<String, Object> result) {
        if (!AUDIT_SCHEMAS.contains(text(result.get("result_schema_version")))
                || !"EVIDENCE_AUDIT".equals(text(result.get("role")))) throw invalid("Audit result schema or role is invalid");
        Map<String, Object> policy = queryPolicy(task);
        Map<String, Object> input = mapValue(policy.get("audit_input"));
        if (input.isEmpty() || !text(input.get("input_digest")).equals(text(result.get("input_digest")))) {
            throw stale("Audit input digest is stale");
        }
        String status = text(result.get("status"));
        if (!Set.of("PASS", "REPAIR_REQUIRED", "BLOCKED").contains(status)) throw invalid("Audit status is invalid");
        Set<String> targetCells = targetCells(task);
        for (Map<String, Object> target : listOfMaps(result.get("targets"))) {
            if (!targetCells.contains(text(target.get("cell_key")))) throw stale("Audit target is outside task snapshot");
        }
    }

    private void validateSynthesis(ResearchAgentCompletionCommitter.TaskRow task, Map<String, Object> result) {
        String schema = text(result.get("result_schema_version"));
        if (!SYNTHESIS_SCHEMAS.contains(schema)
                || !"SYNTHESIS".equals(text(result.get("role")))) throw invalid("Synthesis result schema or role is invalid");
        Map<String, Object> input = mapValue(queryPolicy(task).get("synthesis_input"));
        if (input.isEmpty() || !text(input.get("ledger_digest")).equals(text(result.get("ledger_digest")))
                || !text(input.get("audit_digest")).equals(text(result.get("audit_digest")))) {
            throw stale("Synthesis ledger or audit digest is stale");
        }
        if (input.containsKey("requirement_coverage")) {
            Map<String, Object> current = new ResearchRequirementCoverageService(jdbcTemplate, objectMapper).readModel(task.runId());
            if (!canonicalizer.domainSeparatedDigest("research-synthesis-coverage.v1", current).equals(text(input.get("coverage_digest"))))
                throw stale("成文期间需求或事实发生变化，需要重新生成报告");
            validateAccumulatedFacts(input, result);
        }
        Set<String> targetCells = targetCells(task);
        List<Map<String, Object>> claims = listOfMaps(result.get("claims"));
        synthesisValidationService.validate(
                targetCells,
                listOfMaps(input.get("cells")),
                claims,
                text(result.get("markdown")),
                mapValue(result.get("narrative")),
                text(input.get("question")),
                comparisonTableRequired(text(input.get("question")), java.util.stream.Stream.concat(
                        listOfMaps(input.get("cells")).stream(), listOfMaps(input.get("leads")).stream()).toList()),
                "research-synthesis-candidate.v2".equals(schema),
                listOfMaps(input.get("leads")));
    }

    /** 累计事实必须出现在绑定原单元格和完整联合引用的正文中，缺项说明也必须保留。 */
    private void validateAccumulatedFacts(Map<String, Object> input, Map<String, Object> result) {
        Map<String, Object> narrative = mapValue(result.get("narrative"));
        List<Map<String, Object>> paragraphs = listOfMaps(narrative.get("sections")).stream()
                .flatMap(section -> listOfMaps(section.get("paragraphs")).stream()).toList();
        for (Map<String, Object> cell : listOfMaps(input.get("cells"))) {
            List<Map<String, Object>> facts = listOfMaps(cell.get("facts"));
            if (facts.isEmpty()) facts = List.of(Map.of("claim_text", cell.get("candidate_value"), "evidence_keys", cell.get("evidence_keys")));
            for (Map<String, Object> fact : facts) {
                boolean present = paragraphs.stream().anyMatch(paragraph ->
                        stringList(paragraph.get("cell_keys")).contains(text(cell.get("cell_key")))
                        && text(paragraph.get("text")).contains(text(fact.get("claim_text")))
                        && stringList(paragraph.get("evidence_keys")).containsAll(stringList(fact.get("evidence_keys"))));
                if (!present) throw invalid("报告遗漏了已核验事实或其联合引用");
            }
        }
        Set<String> limitations = new HashSet<>(stringList(narrative.get("limitations")));
        for (String limitation : stringList(input.get("limitations"))) {
            if (!limitation.matches("[A-Z0-9_]+") && !limitations.contains(limitation))
                throw invalid("报告遗漏了尚未完成的研究要求");
        }
    }

    private static final List<String> COMPARISON_MARKERS =
            List.of("compare", "comparison", "versus", " vs ", " vs.", "比较", "对比", "差异", "区别", "异同");

    /** 问题是比较类、且已核验的结论与线索涉及至少两个研究对象时必须有对比表，与 Worker 的判断一致。 */
    boolean comparisonTableRequired(String question, List<Map<String, Object>> cells) {
        String normalized = " " + question.toLowerCase(java.util.Locale.ROOT) + " ";
        long entities = cells.stream().map(cell -> text(cell.get("entity_label")).toLowerCase(java.util.Locale.ROOT))
                .filter(label -> !label.isBlank()).distinct().count();
        return COMPARISON_MARKERS.stream().anyMatch(normalized::contains) && entities >= 2;
    }

    private void persistAuditDecisions(
            ResearchAgentCompletionCommitter.TaskRow task,
            Map<String, Object> result,
            String roleResultId
    ) {
        String status = text(result.get("status"));
        for (Map<String, Object> target : listOfMaps(result.get("targets"))) {
            String cellKey = text(target.get("cell_key"));
            String decisionStatus = "PASS".equals(status) ? "RESOLVED" : "OPEN";
            jdbcTemplate.update("""
                    insert into research_verifier_decision(
                        id, research_run_id, decision_scope, decision_type, reason_code,
                        target_id, evidence_ids_json, action_text, decision_status, notes_json)
                    values (?, ?, 'CELL', ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), task.runId(),
                    "PASS".equals(status) ? "EVIDENCE_AUDIT_PASS" : "EVIDENCE_AUDIT_REPAIR_REQUIRED",
                    stringList(target.get("reason_codes")).stream().findFirst().orElse("AUDIT_REPAIR_REQUIRED"), cellKey,
                    Json.write(objectMapper, stringList(target.get("evidence_keys"))),
                    "PASS".equals(status) ? "AUDIT_COMPLETE" : "COUNTERFACTUAL_REPAIR",
                    decisionStatus, Json.write(objectMapper, Map.of("role_result_id", roleResultId)));
        }
    }

    private Map<String, Object> queryPolicy(ResearchAgentCompletionCommitter.TaskRow task) {
        try {
            Map<String, Object> context = objectMapper.readValue(task.executionContextJson(), new TypeReference<>() { });
            return mapValue(context.get("query_policy"));
        } catch (JsonProcessingException exception) {
            throw invalid("Role task query policy is invalid");
        }
    }

    private Set<String> targetCells(ResearchAgentCompletionCommitter.TaskRow task) {
        try {
            return new HashSet<>(objectMapper.readValue(task.targetBindingsJson(), new TypeReference<List<Map<String, Object>>>() { })
                    .stream().map(item -> text(item.get("cell_id"))).toList());
        } catch (JsonProcessingException exception) {
            throw stale("Role task target snapshot is invalid");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item instanceof Map<?, ?>).map(item -> (Map<String, Object>) item).toList();
    }
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(this::text).filter(item -> !item.isBlank()).toList();
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).strip(); }
    private BusinessException invalid(String message) { return new BusinessException("RESEARCH_AGENT_ROLE_RESULT_INVALID", message); }
    private BusinessException stale(String message) { return new BusinessException("RESEARCH_AGENT_ROLE_RESULT_STALE", message); }
}
