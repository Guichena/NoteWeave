package com.noteweave.research;

import static com.noteweave.research.ResearchReadModelMapper.blankIfNull;
import static com.noteweave.research.ResearchReadModelMapper.castMapOrEmpty;
import static com.noteweave.research.ResearchReadModelMapper.extractListOfMaps;
import static com.noteweave.research.ResearchReadModelMapper.firstNonNull;
import static com.noteweave.research.ResearchReadModelMapper.stringValue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.memory.MemoryControlPackResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads persisted research JSON and trace payloads for the run query facade. */
final class ResearchRunQueryPayloadReader {

    private final ObjectMapper objectMapper;

    ResearchRunQueryPayloadReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    MemoryControlPackResponse readControlPack(String json) {
        try {
            return objectMapper.readValue(json, MemoryControlPackResponse.class);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("RESEARCH_CONTROL_PACK_PARSE_FAILED", "研究控制包解析失败");
        }
    }

    Map<String, Object> readPayloadMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("RESEARCH_TRACE_PAYLOAD_PARSE_FAILED", "研究轨迹载荷解析失败");
        }
    }

    Map<String, Object> extractTracePayload(List<ResearchTraceResponse> traces, String traceType) {
        return traces.stream()
                .filter(trace -> traceType.equals(trace.traceType()))
                .reduce((first, second) -> second)
                .map(ResearchTraceResponse::payload)
                .orElse(Map.of());
    }

    String extractDecisionReason(Map<String, Object> verifierPayload) {
        String directReason = blankIfNull(stringValue(firstNonNull(
                verifierPayload.get("reason_code"),
                verifierPayload.get("reason")
        )));
        if (!directReason.isBlank()) {
            return directReason;
        }
        List<Map<String, Object>> decisionRecords = extractListOfMaps(verifierPayload.get("decision_records"));
        if (decisionRecords.isEmpty()) {
            return "";
        }
        Map<String, Object> latestRecord = decisionRecords.get(decisionRecords.size() - 1);
        return blankIfNull(stringValue(firstNonNull(
                latestRecord.get("reason_code"),
                latestRecord.get("reason")
        )));
    }

    Map<String, Object> extractRecoveryTargets(
            List<ResearchTraceResponse> traces,
            ResearchCheckpointProcessAssembler checkpointProcessAssembler
    ) {
        Map<String, Object> reportStructure = extractNestedMap(traces, "REPORT_STRUCTURE", "report_structure");
        if (reportStructure.isEmpty()) {
            Map<String, Object> finalResultPayload = extractNestedMap(traces, "FINAL_REPORT", "result_payload");
            reportStructure = castMapOrEmpty(finalResultPayload.get("report_structure"));
            if (reportStructure.isEmpty()) {
                return checkpointProcessAssembler.buildRecoveryTargetsFromStopContract(
                        castMapOrEmpty(finalResultPayload.get("stop_contract")));
            }
        }
        Map<String, Object> closedLoopState = castMapOrEmpty(reportStructure.get("closed_loop_state"));
        Map<String, Object> direct = castMapOrEmpty(firstNonNull(
                closedLoopState.get("recovery_targets"),
                castMapOrEmpty(reportStructure.get("recovery_status")).get("recovery_targets")
        ));
        return direct.isEmpty() ? Map.of() : direct;
    }

    Map<String, Object> recoveryTargetsMap(ResearchRecoveryTargetsResponse recoveryTargets) {
        if (recoveryTargets == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("requirement_ids", recoveryTargets.requirementIds());
        value.put("requirement_types", recoveryTargets.requirementTypes());
        value.put("requirement_labels", recoveryTargets.requirementLabels());
        value.put("target_columns", recoveryTargets.targetColumns());
        value.put("target_queries", recoveryTargets.targetQueries());
        value.put("target_sources", recoveryTargets.targetSources());
        value.put("requirement_count", recoveryTargets.requirementCount());
        value.put("query_count", recoveryTargets.queryCount());
        value.put("source_count", recoveryTargets.sourceCount());
        value.put("column_count", recoveryTargets.columnCount());
        return value;
    }

    private Map<String, Object> extractNestedMap(
            List<ResearchTraceResponse> traces,
            String traceType,
            String key
    ) {
        Map<String, Object> payload = extractTracePayload(traces, traceType);
        return castMapOrEmpty(payload.get(key));
    }
}
