package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Captures rollout decisions at Run creation so enabling a flag never mutates legacy Runs. */
@Component
class ResearchAgentFeatureFlagService {
    static final String STRICT_EVIDENCE = "strict_research_evidence_validation";
    static final String INTENT_MATRIX = "intent_matrix_v2";
    static final String RUNNABLE_WORK = "runnable_work_v2";
    static final String CHECKPOINT_HYDRATION = "checkpoint_hydration_v2";
    static final String EVIDENCE_AUDIT = "evidence_audit_v1";
    static final String WORKER_SYNTHESIS = "worker_synthesis_v1";
    static final String WIDE_DISCOVERY = "wide_discovery_v1";
    static final String WIDE_DISCOVERY_AUTO_ACCEPT = "wide_discovery_auto_accept";
    static final String REQUIREMENT_COVERAGE = "requirement_coverage_v1";

    @Value("${noteweave.research.requirement-coverage-v1:false}")
    private boolean requirementCoverage;
    @Value("${noteweave.research.requirement-shadow-mode:false}")
    private boolean requirementShadow;
    @Value("${noteweave.research.requirement-fact-accumulation:true}")
    private boolean factAccumulation;
    @Value("${noteweave.research.requirement-driven-scheduling:true}")
    private boolean drivenScheduling;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, Object> current;

    ResearchAgentFeatureFlagService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${noteweave.research.strict-research-evidence-validation:false}") boolean strictEvidence,
            @Value("${noteweave.research.intent-matrix-v2:false}") boolean intentMatrix,
            @Value("${noteweave.research.runnable-work-v2:false}") boolean runnableWork,
            @Value("${noteweave.research.checkpoint-hydration-v2:false}") boolean checkpointHydration,
            @Value("${noteweave.research.evidence-audit-v1:true}") boolean evidenceAudit,
            @Value("${noteweave.research.worker-synthesis-v1:false}") boolean workerSynthesis,
            @Value("${noteweave.research.wide-discovery-v1:true}") boolean wideDiscovery,
            @Value("${noteweave.research.wide-discovery-auto-accept:false}") boolean discoveryAutoAccept,
            @Value("${noteweave.research.wide-discovery-accepted-precision:0.0}") double acceptedPrecision,
            @Value("${noteweave.research.wide-discovery-precision-threshold:0.90}") double precisionThreshold
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        LinkedHashMap<String, Object> flags = new LinkedHashMap<>();
        flags.put(STRICT_EVIDENCE, strictEvidence);
        flags.put(INTENT_MATRIX, intentMatrix);
        flags.put(RUNNABLE_WORK, runnableWork);
        flags.put(CHECKPOINT_HYDRATION, checkpointHydration);
        flags.put(EVIDENCE_AUDIT, evidenceAudit);
        flags.put(WORKER_SYNTHESIS, workerSynthesis);
        flags.put(WIDE_DISCOVERY, wideDiscovery);
        flags.put(WIDE_DISCOVERY_AUTO_ACCEPT, discoveryAutoAccept);
        flags.put("wide_discovery_accepted_precision", acceptedPrecision);
        flags.put("wide_discovery_precision_threshold", precisionThreshold);
        this.current = Map.copyOf(flags);
    }

    String captureJson() {
        Map<String, Object> captured = new LinkedHashMap<>(current);
        captured.put(REQUIREMENT_COVERAGE, requirementCoverage);
        captured.put("requirement_definition_version", 2);
        captured.put("requirement_state_version", 5);
        captured.put(ResearchAnswerPolicy.FLAG, 1);
        captured.put(ResearchCompletionGapPolicy.FLAG, 1);
        captured.put("completion_budget_reserves", Map.of("SYNTHESIS",16,"EVIDENCE_AUDIT",80,"COUNTERFACTUAL",68));
        captured.put("completion_search_reserves",Map.of("COUNTERFACTUAL",12));
        captured.put("completion_budget_limits",ResearchDiscoveryBudget.LIMITS);
        captured.put("requirement_shadow_mode",requirementShadow);
        captured.put("requirement_fact_accumulation",factAccumulation);
        captured.put("requirement_driven_scheduling",drivenScheduling);
        return Json.write(objectMapper, captured);
    }

    boolean currentEnabled(String key) {
        if (REQUIREMENT_COVERAGE.equals(key)) return requirementCoverage;
        return Boolean.TRUE.equals(current.get(key));
    }

    boolean enabledForRun(String runId, String key) {
        return currentEnabled(key) && Boolean.TRUE.equals(snapshot(runId).get(key));
    }

    double numberForRun(String runId, String key, double fallback) {
        Object value = snapshot(runId).get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private Map<String, Object> snapshot(String runId) {
        String raw = jdbcTemplate.query("""
                select agent_feature_flags_json from research_run where id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, runId);
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            // JSON 列在部分驱动下以带引号的字符串返回，需要再解析一层
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(raw);
            if (node.isTextual()) node = objectMapper.readTree(node.asText());
            if (!node.isObject()) throw new IllegalStateException("Research Agent feature flag snapshot is not an object");
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Research Agent feature flag snapshot is invalid", exception);
        }
    }
}
