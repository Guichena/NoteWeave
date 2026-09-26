package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.strategy.EvidenceBundleSnapshot;
import com.noteweave.answer.strategy.RetrievalExecutionTrace;
import java.util.ArrayList;
import java.util.List;

/** Strictly decodes and validates persisted QA retrieval artifacts before export. */
final class QaAnswerRunShadowArtifactValidator {

    private final ObjectMapper objectMapper;

    QaAnswerRunShadowArtifactValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    EvidenceBundleSnapshot readBundle(QaAnswerRunShadowExportReadRepository.RunRow run) {
        if (run.bundleJson().isBlank()) {
            throw new IllegalStateException("AnswerRun has no persisted EvidenceBundle");
        }
        try {
            JsonNode root = objectMapper.readTree(run.bundleJson());
            validateBundleShape(root);
            return objectMapper.treeToValue(root, EvidenceBundleSnapshot.class);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "AnswerRun EvidenceBundle cannot be decoded", ex);
        }
    }

    SummarySnapshot readSummary(String runId, String summaryJson) {
        try {
            JsonNode root = objectMapper.readTree(summaryJson);
            JsonNode trace = root.path("execution_trace");
            if (trace.isMissingNode() || trace.isNull()) {
                throw new IllegalStateException(
                        "AnswerRun retrieval.summary has no execution_trace");
            }
            validateTraceShape(trace);
            return new SummarySnapshot(
                    requiredText(root, "trace_schema_version", runId),
                    requiredText(root, "plan_version", runId),
                    optionalText(root, "strategy_profile", runId),
                    optionalText(root, "relevance_policy", runId),
                    optionalText(root, "selection_policy", runId),
                    requiredLong(root, "retrieval_latency_micros", runId),
                    requiredInt(root, "candidate_count", runId),
                    requiredInt(root, "admitted_candidate_count", runId),
                    requiredInt(root, "selected_evidence_count", runId),
                    requiredInt(root, "selected_evidence_characters", runId),
                    requiredBoolean(root, "degraded", runId),
                    requiredStrings(root, "degradation_reasons", runId),
                    objectMapper.treeToValue(trace, RetrievalExecutionTrace.class)
            );
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "AnswerRun retrieval.summary cannot be decoded", ex);
        }
    }

    private void validateBundleShape(JsonNode root) {
        requireObject(root, "evidence_bundle");
        if (!EvidenceBundleSnapshot.SCHEMA_VERSION.equals(
                requireText(root, "schema_version", true, "evidence_bundle"))) {
            throw invalidJson("evidence_bundle", "schema_version");
        }
        requireText(root, "bundle_id", true, "evidence_bundle");
        requireText(root, "retrieval_plan_version", true, "evidence_bundle");
        requireBoolean(root, "degraded", "evidence_bundle");
        requireStringArray(root, "degradation_reasons", "evidence_bundle");
        requirePresent(root, "created_at", "evidence_bundle");
        JsonNode evidence = requireArray(root, "evidence", "evidence_bundle");
        for (JsonNode item : evidence) {
            requireObject(item, "evidence");
            requirePositiveInt(item, "rank", "evidence");
            requireText(item, "evidence_id", true, "evidence");
            requireText(item, "kind", true, "evidence");
            requireText(item, "source_id", true, "evidence");
            requireText(item, "source_snapshot_id", true, "evidence");
            requireText(item, "passage_id", true, "evidence");
            requireText(item, "knowledge_item_id", false, "evidence");
            requireText(item, "knowledge_version_id", false, "evidence");
            requireFiniteNumber(item, "raw_score", "evidence");
            requireFiniteNumber(item, "fused_score", "evidence");
            requireFiniteNumber(item, "rerank_score", "evidence");
            requireText(item, "access_scope", true, "evidence");
            requirePresent(item, "fresh_at", "evidence");
            requireText(item, "selection_reason", false, "evidence");
            requireNonNegativeInt(item, "character_cost", "evidence");
        }
    }

    private void validateTraceShape(JsonNode trace) {
        requireObject(trace, "execution_trace");
        if (!RetrievalExecutionTrace.SCHEMA_VERSION.equals(
                requireText(trace, "schema_version", true, "execution_trace"))) {
            throw invalidJson("execution_trace", "schema_version");
        }
        requireText(trace, "plan_version", true, "execution_trace");
        requireNonNegativeLong(trace, "total_latency_micros", "execution_trace");
        requireNonNegativeInt(trace, "raw_candidate_count", "execution_trace");
        requireNonNegativeInt(trace, "admitted_candidate_count", "execution_trace");
        requireNonNegativeInt(trace, "selected_evidence_count", "execution_trace");
        requireNonNegativeInt(trace, "selected_evidence_characters", "execution_trace");
        JsonNode steps = requireArray(trace, "steps", "execution_trace");
        for (JsonNode step : steps) {
            requireObject(step, "execution_step");
            requireNonNegativeInt(step, "step_index", "execution_step");
            requireText(step, "channel", true, "execution_step");
            requireNonNegativeInt(step, "candidate_limit", "execution_step");
            requireNonNegativeInt(step, "raw_candidate_count", "execution_step");
            requireNonNegativeInt(step, "admitted_candidate_count", "execution_step");
            requireNonNegativeLong(step, "latency_micros", "execution_step");
            requireBoolean(step, "degraded", "execution_step");
            requireStringArray(step, "degradation_reasons", "execution_step");
            JsonNode measurements = requireObjectField(step, "measurements", "execution_step");
            measurements.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
                    throw invalidJson("execution_step.measurements", entry.getKey());
                }
            });
        }
        JsonNode selected = requireArray(trace, "selected_evidence", "execution_trace");
        for (JsonNode item : selected) {
            requireObject(item, "selected_evidence");
            requirePositiveInt(item, "rank", "selected_evidence");
            requireText(item, "evidence_id", true, "selected_evidence");
            requireText(item, "kind", true, "selected_evidence");
            requireFiniteNumber(item, "raw_score", "selected_evidence");
            requireFiniteNumber(item, "fused_score", "selected_evidence");
            requireFiniteNumber(item, "rerank_score", "selected_evidence");
            requireNonNegativeInt(item, "character_cost", "selected_evidence");
        }
    }

    private void requireObject(JsonNode value, String artifact) {
        if (value == null || !value.isObject()) {
            throw invalidJson(artifact, "root");
        }
    }

    private JsonNode requireObjectField(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isObject()) {
            throw invalidJson(artifact, field);
        }
        return value;
    }

    private JsonNode requireArray(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            throw invalidJson(artifact, field);
        }
        return value;
    }

    private String requireText(JsonNode root, String field, boolean nonBlank, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || (nonBlank && value.textValue().isBlank())) {
            throw invalidJson(artifact, field);
        }
        return value.textValue();
    }

    private void requirePresent(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw invalidJson(artifact, field);
        }
    }

    private void requireBoolean(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalidJson(artifact, field);
        }
    }

    private void requireStringArray(JsonNode root, String field, String artifact) {
        JsonNode values = requireArray(root, field, artifact);
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw invalidJson(artifact, field);
            }
        }
    }

    private void requirePositiveInt(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt() || value.intValue() <= 0) {
            throw invalidJson(artifact, field);
        }
    }

    private void requireNonNegativeInt(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalidJson(artifact, field);
        }
    }

    private void requireNonNegativeLong(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalidJson(artifact, field);
        }
    }

    private void requireFiniteNumber(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw invalidJson(artifact, field);
        }
    }

    private IllegalStateException invalidJson(String artifact, String field) {
        return new IllegalStateException(
                "Persisted " + artifact + " JSON field is missing or invalid: " + field);
    }

    private String requiredText(JsonNode root, String field, String runId) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalidSummary(runId, field);
        }
        return value.textValue();
    }

    private String optionalText(JsonNode root, String field, String runId) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isTextual()) {
            throw invalidSummary(runId, field);
        }
        return value.textValue();
    }

    private int requiredInt(JsonNode root, String field, String runId) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalidSummary(runId, field);
        }
        return value.intValue();
    }

    private long requiredLong(JsonNode root, String field, String runId) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalidSummary(runId, field);
        }
        return value.longValue();
    }

    private boolean requiredBoolean(JsonNode root, String field, String runId) {
        JsonNode value = root.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalidSummary(runId, field);
        }
        return value.booleanValue();
    }

    private List<String> requiredStrings(JsonNode root, String field, String runId) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            throw invalidSummary(runId, field);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw invalidSummary(runId, field);
            }
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }

    private IllegalStateException invalidSummary(String runId, String field) {
        return new IllegalStateException(
                "AnswerRun retrieval.summary field is invalid: " + field);
    }

    record SummarySnapshot(
            String traceSchemaVersion,
            String planVersion,
            String strategyProfile,
            String relevancePolicy,
            String selectionPolicy,
            long retrievalLatencyMicros,
            int candidateCount,
            int admittedCandidateCount,
            int selectedEvidenceCount,
            int selectedEvidenceCharacters,
            boolean degraded,
            List<String> degradationReasons,
            RetrievalExecutionTrace trace
    ) {
    }
}
