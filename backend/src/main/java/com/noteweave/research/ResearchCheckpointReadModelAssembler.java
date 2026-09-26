package com.noteweave.research;

import static com.noteweave.research.ResearchReadModelMapper.castMapOrEmpty;
import static com.noteweave.research.ResearchReadModelMapper.extractStringList;
import static com.noteweave.research.ResearchReadModelMapper.intValue;
import static com.noteweave.research.ResearchReadModelMapper.readCheckpointSnapshotSummaryResponse;
import static com.noteweave.research.ResearchReadModelMapper.readCounterfactualSummary;
import static com.noteweave.research.ResearchReadModelMapper.readRecoveryTargetsResponse;
import static com.noteweave.research.ResearchReadModelMapper.stringValue;
import static com.noteweave.research.ResearchReadModelMapper.blankIfNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Maps checkpoint persistence rows into the maps and summaries consumed by research read models. */
@Component
public class ResearchCheckpointReadModelAssembler {
    private final ObjectMapper objectMapper;
    private final ResearchSourceProvenanceEnricher sourceProvenanceEnricher;

    public ResearchCheckpointReadModelAssembler(
            ObjectMapper objectMapper,
            ResearchSourceProvenanceEnricher sourceProvenanceEnricher
    ) {
        this.objectMapper = objectMapper;
        this.sourceProvenanceEnricher = sourceProvenanceEnricher;
    }

    public Map<String, Object> toPersistedCheckpoint(
            ResearchCheckpointRecord row,
            boolean enrichSourceProvenance
    ) {
        LinkedHashMap<String, Object> checkpoint = new LinkedHashMap<>();
        Map<String, Object> summary = readPayloadMap(row.summaryJson());
        if (enrichSourceProvenance) {
            sourceProvenanceEnricher.enrich(summary);
        }
        Map<String, Object> loopDecision = castMapOrEmpty(summary.get("loop_decision"));
        Map<String, Object> localVerifier = castMapOrEmpty(summary.get("local_verifier"));
        Map<String, Object> globalVerifier = castMapOrEmpty(summary.get("global_verifier"));
        Map<String, Object> stateLedger = castMapOrEmpty(summary.get("state_ledger"));
        Map<String, Object> researchIntentAlignment = castMapOrEmpty(summary.get("research_intent_alignment"));
        Map<String, Object> counterfactualSummary = castMapOrEmpty(summary.get("counterfactual_summary"));
        Map<String, Object> recoveryTargets = castMapOrEmpty(summary.get("recovery_targets"));
        checkpoint.put("checkpoint_no", row.checkpointNo());
        checkpoint.put("snapshot_type", row.snapshotType());
        checkpoint.put("object_key", row.objectKey());
        checkpoint.put("payload_sha256", row.payloadSha256());
        checkpoint.put("content_size", row.contentSize());
        checkpoint.put("active_branch_id", blankIfNull(row.activeBranchKey()));
        checkpoint.put("final_loop_decision", blankIfNull(row.finalLoopDecision()));
        checkpoint.put("summary", summary);
        checkpoint.put("loop_decision", loopDecision);
        checkpoint.put("local_verifier", localVerifier);
        checkpoint.put("global_verifier", globalVerifier);
        checkpoint.put("state_ledger", stateLedger);
        checkpoint.put("research_intent_alignment", researchIntentAlignment.isEmpty() ? null : researchIntentAlignment);
        checkpoint.put("counterfactual_summary", counterfactualSummary.isEmpty() ? null : counterfactualSummary);
        checkpoint.put("recovery_targets", recoveryTargets.isEmpty() ? null : recoveryTargets);
        checkpoint.put("local_verifier_status", blankIfNull(stringValue(localVerifier.get("status"))));
        checkpoint.put("global_verifier_decision", blankIfNull(stringValue(globalVerifier.get("decision"))));
        checkpoint.put("research_intent_alignment_status", blankIfNull(stringValue(summary.get("research_intent_alignment_status"))));
        checkpoint.put("research_intent_alignment_reason", blankIfNull(stringValue(summary.get("research_intent_alignment_reason"))));
        checkpoint.put("intent_constraint_count", intValue(summary.get("intent_constraint_count")));
        checkpoint.put("intent_satisfied_constraint_count", intValue(summary.get("intent_satisfied_constraint_count")));
        checkpoint.put("intent_requirement_count", intValue(summary.get("intent_requirement_count")));
        checkpoint.put("intent_satisfied_requirement_count", intValue(summary.get("intent_satisfied_requirement_count")));
        checkpoint.put("intent_pending_requirement_count", intValue(summary.get("intent_pending_requirement_count")));
        checkpoint.put("missing_intent_requirements", extractStringList(summary.get("missing_intent_requirements")));
        checkpoint.put("verified_row_count", intValue(stateLedger.get("verified_row_count")));
        checkpoint.put("conflicted_row_count", intValue(stateLedger.get("conflicted_row_count")));
        checkpoint.put("created_at", row.createdAt());
        return checkpoint;
    }

    public ResearchCheckpointSummaryResponse toSummary(Map<String, Object> checkpoint) {
        return new ResearchCheckpointSummaryResponse(
                intValue(checkpoint.get("checkpoint_no")),
                blankIfNull(stringValue(checkpoint.get("snapshot_type"))),
                blankIfNull(stringValue(checkpoint.get("active_branch_id"))),
                blankIfNull(stringValue(checkpoint.get("final_loop_decision"))),
                blankIfNull(stringValue(checkpoint.get("local_verifier_status"))),
                blankIfNull(stringValue(checkpoint.get("global_verifier_decision"))),
                intValue(checkpoint.get("verified_row_count")),
                intValue(checkpoint.get("conflicted_row_count")),
                readCheckpointSnapshotSummaryResponse(castMapOrEmpty(checkpoint.get("summary"))),
                readCounterfactualSummary(castMapOrEmpty(checkpoint.get("counterfactual_summary"))),
                readRecoveryTargetsResponse(castMapOrEmpty(checkpoint.get("recovery_targets"))),
                (Instant) checkpoint.get("created_at")
        );
    }

    private Map<String, Object> readPayloadMap(String json) {
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
}
