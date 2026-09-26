package com.noteweave.research;

import static com.noteweave.research.ResearchReadModelMapper.castMapOrEmpty;
import static com.noteweave.research.ResearchReadModelMapper.extractListOfMaps;
import static com.noteweave.research.ResearchReadModelMapper.extractStringList;
import static com.noteweave.research.ResearchReadModelMapper.firstNonNull;
import static com.noteweave.research.ResearchReadModelMapper.nonEmptyMapOrNull;
import static com.noteweave.research.ResearchReadModelMapper.readCounterfactualSummary;
import static com.noteweave.research.ResearchReadModelMapper.readResearchArtifactCandidateResponse;
import static com.noteweave.research.ResearchReadModelMapper.readResumeContextSummaryResponse;
import static com.noteweave.research.ResearchReadModelMapper.stringValue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Maps research trace report payloads into stable report and intent read models. */
@Component
public class ResearchReportReadModelAssembler {
    private final ObjectMapper objectMapper;

    public ResearchReportReadModelAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ResearchReportStructureResponse buildReportStructure(List<ResearchTraceResponse> traces) {
        Map<String, Object> reportStructure = extractNestedMap(traces, "REPORT_STRUCTURE", "report_structure");
        if (reportStructure.isEmpty()) {
            Map<String, Object> finalResultPayload = extractNestedMap(traces, "FINAL_REPORT", "result_payload");
            reportStructure = castMapOrEmpty(finalResultPayload.get("report_structure"));
        }
        if (reportStructure.isEmpty()) {
            return null;
        }
        return new ResearchReportStructureResponse(
                castMapOrEmpty(reportStructure.get("research_question")),
                readResearchIntent(castMapOrEmpty(reportStructure.get("research_intent"))),
                castMapOrEmpty(reportStructure.get("intent_completion_contract")),
                readResearchIntentAlignment(castMapOrEmpty(reportStructure.get("research_intent_alignment"))),
                extractStringList(reportStructure.get("key_findings")),
                extractListOfMaps(reportStructure.get("verified_findings")),
                extractListOfMaps(reportStructure.get("evidence_ledger")),
                castMapOrEmpty(reportStructure.get("closed_loop_state")),
                readCounterfactualSummary(castMapOrEmpty(reportStructure.get("counterfactual_summary"))),
                castMapOrEmpty(reportStructure.get("conflict_and_counterfactual_review")),
                castMapOrEmpty(reportStructure.get("recovery_status")),
                castMapOrEmpty(reportStructure.get("final_answer")),
                castMapOrEmpty(reportStructure.get("source_foundation")),
                extractStringList(reportStructure.get("next_actions")),
                castMapOrEmpty(reportStructure.get("resume_checkpoint")),
                stringValue(reportStructure.get("recovery_mode")),
                extractStringList(reportStructure.get("control_notes"))
        );
    }

    public ResearchArtifactCandidateResponse buildResearchArtifactCandidate(List<ResearchTraceResponse> traces) {
        Map<String, Object> finalResultPayload = extractNestedMap(traces, "FINAL_REPORT", "result_payload");
        return readResearchArtifactCandidateResponse(nonEmptyMapOrNull(firstNonNull(
                finalResultPayload.get("research_artifact_candidate"),
                castMapOrEmpty(finalResultPayload.get("research_checkpoint_candidate"))
                        .get("research_artifact_candidate")
        )));
    }

    public ResearchResumeContextSummaryResponse buildResumeContextSummary(List<ResearchTraceResponse> traces) {
        Map<String, Object> finalResultPayload = extractNestedMap(traces, "FINAL_REPORT", "result_payload");
        return readResumeContextSummaryResponse(nonEmptyMapOrNull(firstNonNull(
                finalResultPayload.get("resume_context_summary"),
                firstNonNull(
                        castMapOrEmpty(finalResultPayload.get("research_artifact_candidate"))
                                .get("resume_context_summary"),
                        castMapOrEmpty(finalResultPayload.get("research_checkpoint_candidate"))
                                .get("resume_context_summary")
                )
        )));
    }

    public ResearchIntentResponse readResearchIntent(String json) {
        if (json == null || json.isBlank()) {
            return ResearchIntentPolicy.defaultIntent();
        }
        try {
            return ResearchIntentPolicy.normalize(
                    objectMapper.readValue(json, ResearchIntentResponse.class));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("RESEARCH_INTENT_PARSE_FAILED", "研究意图解析失败");
        }
    }

    public ResearchIntentResponse readResearchIntent(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return ResearchIntentPolicy.defaultIntent();
        }
        return ResearchIntentPolicy.normalize(
                objectMapper.convertValue(payload, ResearchIntentResponse.class));
    }

    public ResearchIntentAlignmentResponse readResearchIntentAlignment(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        return objectMapper.convertValue(payload, ResearchIntentAlignmentResponse.class);
    }

    private Map<String, Object> extractNestedMap(
            List<ResearchTraceResponse> traces,
            String traceType,
            String key
    ) {
        return castMapOrEmpty(extractTracePayload(traces, traceType).get(key));
    }

    private Map<String, Object> extractTracePayload(List<ResearchTraceResponse> traces, String traceType) {
        return traces.stream()
                .filter(trace -> traceType.equals(trace.traceType()))
                .reduce((first, second) -> second)
                .map(ResearchTraceResponse::payload)
                .orElse(Map.of());
    }
}
