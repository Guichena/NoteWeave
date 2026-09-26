package com.noteweave.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.memory.MemoryControlPackResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Parses persisted artifact payloads and normalizes runtime trace compatibility fields. */
@Component
public class ArtifactPayloadReadModelAssembler {
    private final ObjectMapper objectMapper;

    public ArtifactPayloadReadModelAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MemoryControlPackResponse readControlPack(String json) {
        try {
            return objectMapper.readValue(json, MemoryControlPackResponse.class);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("ARTIFACT_CONTROL_PACK_PARSE_FAILED", "产物控制包解析失败");
        }
    }

    public Map<String, Object> readInputs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("ARTIFACT_INPUTS_PARSE_FAILED", "产物 Skill 输入解析失败");
        }
    }

    public List<Map<String, Object>> readCitations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("ARTIFACT_CITATIONS_PARSE_FAILED", "产物引用解析失败");
        }
    }

    public ArtifactRuntimeTraceResponse readRuntimeTrace(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("ARTIFACT_RESULT_PAYLOAD_PARSE_FAILED", "产物结果载荷解析失败");
        }
        Object nestedRuntimeTrace = payload.get("runtime_trace");
        Map<String, Object> runtimeTracePayload;
        if (nestedRuntimeTrace instanceof Map<?, ?> nestedMap) {
            runtimeTracePayload = normalizeObjectMap(nestedMap);
        } else {
            LinkedHashMap<String, Object> runtimeTrace = new LinkedHashMap<>();
            copyRuntimeTraceField(payload, runtimeTrace, "verification");
            copyRuntimeTraceField(payload, runtimeTrace, "generation_trace");
            copyRuntimeTraceField(payload, runtimeTrace, "export_trace");
            copyRuntimeTraceField(payload, runtimeTrace, "node_traces");
            copyRuntimeTraceField(payload, runtimeTrace, "capability_union_trace");
            copyRuntimeTraceField(payload, runtimeTrace, "approval_trace");
            copyRuntimeTraceField(payload, runtimeTrace, "evidence_coverage");
            copyRuntimeTraceField(payload, runtimeTrace, "writeback_preview");
            copyRuntimeTraceField(payload, runtimeTrace, "output_contract_trace");
            copyRuntimeTraceField(payload, runtimeTrace, "lifecycle_trace");
            copyRuntimeTraceField(payload, runtimeTrace, "acquisition_callback_trace");
            runtimeTracePayload = runtimeTrace;
        }
        runtimeTracePayload = normalizeAndStripLegacyActionKeys(runtimeTracePayload);
        if (runtimeTracePayload.isEmpty()) {
            return null;
        }
        return new ArtifactRuntimeTraceResponse(
                convertTraceValue(
                        runtimeTracePayload.get("verification"),
                        ArtifactVerificationTraceResponse.class,
                        "ARTIFACT_RUNTIME_VERIFICATION_PARSE_FAILED",
                        "产物运行时 verification trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("generation_trace"),
                        ArtifactGenerationTraceResponse.class,
                        "ARTIFACT_RUNTIME_GENERATION_TRACE_PARSE_FAILED",
                        "产物运行时 generation trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("export_trace"),
                        ArtifactExportTraceResponse.class,
                        "ARTIFACT_RUNTIME_EXPORT_TRACE_PARSE_FAILED",
                        "产物运行时 export trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("approval_trace"),
                        ArtifactApprovalTraceResponse.class,
                        "ARTIFACT_RUNTIME_APPROVAL_TRACE_PARSE_FAILED",
                        "产物运行时 approval trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("capability_union_trace"),
                        ArtifactCapabilityUnionTraceResponse.class,
                        "ARTIFACT_RUNTIME_CAPABILITY_UNION_PARSE_FAILED",
                        "产物运行时 capability union trace 解析失败"
                ),
                convertTraceList(
                        runtimeTracePayload.get("node_traces"),
                        ArtifactNodeTraceResponse.class,
                        "ARTIFACT_RUNTIME_NODE_TRACES_PARSE_FAILED",
                        "产物运行时 node traces 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("evidence_coverage"),
                        ArtifactEvidenceCoverageTraceResponse.class,
                        "ARTIFACT_RUNTIME_EVIDENCE_COVERAGE_PARSE_FAILED",
                        "产物运行时 evidence coverage trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("writeback_preview"),
                        ArtifactWritebackPreviewTraceResponse.class,
                        "ARTIFACT_RUNTIME_WRITEBACK_PREVIEW_PARSE_FAILED",
                        "产物运行时 writeback preview trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("output_contract_trace"),
                        ArtifactOutputContractTraceResponse.class,
                        "ARTIFACT_RUNTIME_OUTPUT_CONTRACT_PARSE_FAILED",
                        "产物运行时 output contract trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("lifecycle_trace"),
                        ArtifactLifecycleTraceResponse.class,
                        "ARTIFACT_RUNTIME_LIFECYCLE_PARSE_FAILED",
                        "产物运行时 lifecycle trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("acquisition_callback_trace"),
                        ArtifactAcquisitionCallbackTraceResponse.class,
                        "ARTIFACT_RUNTIME_ACQUISITION_CALLBACK_PARSE_FAILED",
                        "产物运行时 acquisition callback trace 解析失败"
                )
        );
    }

    private void copyRuntimeTraceField(
            Map<String, Object> payload,
            Map<String, Object> runtimeTrace,
            String fieldName
    ) {
        if (payload.containsKey(fieldName) && payload.get(fieldName) != null) {
            runtimeTrace.put(fieldName, payload.get(fieldName));
        }
    }

    private Map<String, Object> normalizeObjectMap(Map<?, ?> rawMap) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return normalized;
    }

    private Map<String, Object> normalizeAndStripLegacyActionKeys(Map<String, Object> rawMap) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
            if ("action_checks".equals(entry.getKey())) {
                normalized.put("contract_checks", stripLegacyActionKeys(entry.getValue()));
                continue;
            }
            if (Set.of(
                    "action_key",
                    "action_scope",
                    "action_basis",
                    "skill_graph_basis",
                    "requested_action_key",
                    "resolved_action_key",
                    "explicit_requested_action_key",
                    "effective_action_key")
                    .contains(entry.getKey())) {
                continue;
            }
            normalized.put(entry.getKey(), stripLegacyActionKeys(entry.getValue()));
        }
        return normalized;
    }

    private Object stripLegacyActionKeys(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            return normalizeAndStripLegacyActionKeys(normalizeObjectMap(rawMap));
        }
        if (value instanceof List<?> rawList) {
            return rawList.stream().map(this::stripLegacyActionKeys).toList();
        }
        return value;
    }

    private <T> T convertTraceValue(
            Object value,
            Class<T> targetType,
            String errorCode,
            String errorMessage
    ) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(value, targetType);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(errorCode, errorMessage);
        }
    }

    private <T> List<T> convertTraceList(
            Object value,
            Class<T> elementType,
            String errorCode,
            String errorMessage
    ) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> rawList)) {
            throw new BusinessException(errorCode, errorMessage);
        }
        try {
            return rawList.stream()
                    .map(item -> objectMapper.convertValue(item, elementType))
                    .toList();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(errorCode, errorMessage);
        }
    }
}
