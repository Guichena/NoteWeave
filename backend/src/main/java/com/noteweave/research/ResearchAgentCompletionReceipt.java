package com.noteweave.research;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Immutable canonical receipt; replay metadata is not part of its digest. */
public record ResearchAgentCompletionReceipt(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("completion_id") String completionId,
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("completion_digest") String completionDigest,
        @JsonProperty("receipt_digest") String receiptDigest,
        @JsonProperty("idempotent_replay") boolean idempotentReplay,
        String outcome,
        @JsonProperty("evidence_appended") int evidenceAppended,
        @JsonProperty("candidate_count") int candidateCount,
        @JsonProperty("accepted_merges") List<MergeReceipt> acceptedMerges,
        @JsonProperty("rejected_merges") List<MergeReceipt> rejectedMerges,
        BudgetReceipt budget
) {
    public ResearchAgentCompletionReceipt {
        acceptedMerges = acceptedMerges == null ? null : List.copyOf(acceptedMerges);
        rejectedMerges = rejectedMerges == null ? null : List.copyOf(rejectedMerges);
    }

    public ResearchAgentCompletionReceipt(
            String schemaVersion, String completionId, String executionId, String taskId,
            String completionDigest, String receiptDigest, boolean idempotentReplay,
            int evidenceAppended, int candidateCount, List<MergeReceipt> acceptedMerges,
            List<MergeReceipt> rejectedMerges, BudgetReceipt budget
    ) {
        this(schemaVersion, completionId, executionId, taskId, completionDigest, receiptDigest,
                idempotentReplay, "COMMITTED", evidenceAppended, candidateCount,
                acceptedMerges, rejectedMerges, budget);
    }

    public ResearchAgentCompletionReceipt withIdempotentReplay(boolean replay) {
        return new ResearchAgentCompletionReceipt(
                schemaVersion, completionId, executionId, taskId, completionDigest, receiptDigest, replay, outcome,
                evidenceAppended, candidateCount, acceptedMerges, rejectedMerges, budget
        );
    }

    public record MergeReceipt(
            @JsonProperty("cell_key") String cellKey,
            @JsonProperty("from_version") int fromVersion,
            @JsonProperty("to_version") int toVersion,
            String decision,
            @JsonProperty("reason_code") String reasonCode
    ) { }

    public record BudgetReceipt(
            String state,
            Map<String, Long> reserved,
            Map<String, Long> consumed,
            Map<String, Long> released
    ) {
        public BudgetReceipt {
            reserved = immutableMap(reserved);
            consumed = immutableMap(consumed);
            released = immutableMap(released);
        }
    }

    private static Map<String, Long> immutableMap(Map<String, Long> source) {
        return source == null ? null : Map.copyOf(new java.util.TreeMap<>(source));
    }
}
