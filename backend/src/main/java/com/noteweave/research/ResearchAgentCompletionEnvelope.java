package com.noteweave.research;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Immutable wire contract for {@code research-agent-completion.v1}. */
public record ResearchAgentCompletionEnvelope(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("worker_instance_id") String workerInstanceId,
        @JsonProperty("lease_epoch") int leaseEpoch,
        @JsonProperty("fencing_token") long fencingToken,
        @JsonProperty("execution_key") String executionKey,
        @JsonProperty("task_snapshot_digest") String taskSnapshotDigest,
        @JsonProperty("termination_reason") String terminationReason,
        @JsonProperty("budget_usage") Map<String, Long> budgetUsage,
        Map<String, Long> telemetry,
        @JsonProperty("trace_digest") String traceDigest,
        List<Evidence> evidence,
        List<Candidate> candidates,
        @JsonProperty("envelope_digest") String envelopeDigest
) {
    public ResearchAgentCompletionEnvelope {
        budgetUsage = immutableMap(budgetUsage);
        telemetry = immutableMap(telemetry);
        evidence = evidence == null ? null : List.copyOf(evidence);
        candidates = candidates == null ? null : List.copyOf(candidates);
    }

    public ResearchAgentCompletionEnvelope withEnvelopeDigest(String digest) {
        return new ResearchAgentCompletionEnvelope(
                schemaVersion, taskId, workerInstanceId, leaseEpoch, fencingToken, executionKey,
                taskSnapshotDigest, terminationReason, budgetUsage, telemetry, traceDigest, evidence, candidates, digest
        );
    }

    public record Evidence(
            @JsonProperty("evidence_key") String evidenceKey,
            @JsonProperty("window_id") String windowId,
            @JsonProperty("source_id") String sourceId,
            @JsonProperty("source_title") String sourceTitle,
            @JsonProperty("search_query") String searchQuery,
            @JsonProperty("read_focus") String readFocus,
            @JsonProperty("quote_text") String quoteText,
            @JsonProperty("claim_text") String claimText,
            @JsonProperty("relation_type") String relationType,
            @JsonProperty("support_score_ppm") int supportScorePpm,
            @JsonProperty("conflict_score_ppm") int conflictScorePpm,
            @JsonProperty("snapshot_status") String snapshotStatus
    ) { }

    public record Candidate(
            @JsonProperty("candidate_key") String candidateKey,
            @JsonProperty("cell_key") String cellKey,
            @JsonProperty("base_cell_version") int baseCellVersion,
            @JsonProperty("candidate_value") String candidateValue,
            @JsonProperty("evidence_keys") List<String> evidenceKeys,
            @JsonProperty("confidence_ppm") int confidencePpm
    ) {
        public Candidate {
            evidenceKeys = evidenceKeys == null ? null : List.copyOf(evidenceKeys);
        }

        public Candidate withEvidenceKeys(List<String> keys) {
            return new Candidate(candidateKey, cellKey, baseCellVersion, candidateValue, keys, confidencePpm);
        }
    }

    private static Map<String, Long> immutableMap(Map<String, Long> source) {
        return source == null ? null : Map.copyOf(new java.util.TreeMap<>(source));
    }
}
