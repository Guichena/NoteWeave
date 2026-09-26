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
    private static final String AUDIT_SCHEMA = "research-evidence-audit-result.v1";
    private static final Set<String> SYNTHESIS_SCHEMAS = Set.of(
            "research-synthesis-candidate.v1", "research-synthesis-candidate.v2");
    private static final String DIGEST_DOMAIN = "research-agent-role-result.v1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentCompletionCanonicalizer canonicalizer;
    private final ResearchAgentRoleCapabilityRegistry capabilities;
    private final ResearchDiscoveryProposalService discoveryProposalService;
    private final ResearchAgentSynthesisValidationService synthesisValidationService;

    ResearchAgentRoleResultService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentCompletionCanonicalizer canonicalizer,
            ResearchAgentRoleCapabilityRegistry capabilities,
            ResearchDiscoveryProposalService discoveryProposalService,
            ResearchAgentSynthesisValidationService synthesisValidationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
        this.capabilities = capabilities;
        this.discoveryProposalService = discoveryProposalService;
        this.synthesisValidationService = synthesisValidationService;
    }

    void validateAndPersist(
            ResearchAgentCompletionCommitter.TaskRow task,
            ResearchAgentCompletionEnvelope envelope,
            String completionId
    ) {
        if (!Set.of("EVIDENCE_AUDIT", "SYNTHESIS", "WIDE_DISCOVERY").contains(task.role())) return;
        capabilities.requireRegistered(task.role());
        Map<String, Object> result = envelope.roleResult();
        if (result == null || result.isEmpty()) throw invalid("Role completion has no result payload");
        if ("EVIDENCE_AUDIT".equals(task.role())) {
            validateAudit(task, result);
        } else if ("SYNTHESIS".equals(task.role())) {
            validateSynthesis(task, result);
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
        } else {
            discoveryProposalService.validateAndPersist(task, result, roleResultId);
        }
    }

    private void validateAudit(ResearchAgentCompletionCommitter.TaskRow task, Map<String, Object> result) {
        if (!AUDIT_SCHEMA.equals(text(result.get("result_schema_version")))
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
        Set<String> targetCells = targetCells(task);
        List<Map<String, Object>> claims = listOfMaps(result.get("claims"));
        synthesisValidationService.validate(
                targetCells,
                listOfMaps(input.get("cells")),
                claims,
                text(result.get("markdown")),
                mapValue(result.get("narrative")),
                text(input.get("question")),
                comparisonTableRequired(text(input.get("question"))),
                "research-synthesis-candidate.v2".equals(schema));
    }

    private boolean comparisonTableRequired(String question) {
        String normalized = question.toLowerCase();
        boolean comparison = List.of("compare", "comparison", "versus", " vs ", "比较", "对比", "差异")
                .stream().anyMatch(normalized::contains);
        long products = List.of("postgresql", "mysql", "mongodb", "redis", "openai", "anthropic")
                .stream().filter(normalized::contains).count();
        return comparison && products >= 2;
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
                    text(target.get("reason_codes")), cellKey,
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
