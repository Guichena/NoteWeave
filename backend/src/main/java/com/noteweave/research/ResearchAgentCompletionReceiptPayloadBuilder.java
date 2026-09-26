package com.noteweave.research;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the persisted JSON projection of a completion receipt. */
final class ResearchAgentCompletionReceiptPayloadBuilder {

    Map<String, Object> build(ResearchAgentCompletionReceipt receipt, boolean includeDigest) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", receipt.schemaVersion());
        payload.put("completion_id", receipt.completionId());
        payload.put("execution_id", receipt.executionId());
        payload.put("task_id", receipt.taskId());
        payload.put("completion_digest", receipt.completionDigest());
        payload.put("outcome", receipt.outcome());
        payload.put("evidence_appended", receipt.evidenceAppended());
        payload.put("candidate_count", receipt.candidateCount());
        payload.put("accepted_merges", receipt.acceptedMerges().stream().map(this::mergePayload).toList());
        payload.put("rejected_merges", receipt.rejectedMerges().stream().map(this::mergePayload).toList());

        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("state", receipt.budget().state());
        budget.put("reserved", receipt.budget().reserved());
        budget.put("consumed", receipt.budget().consumed());
        budget.put("released", receipt.budget().released());
        payload.put("budget", budget);
        if (includeDigest) {
            payload.put("receipt_digest", receipt.receiptDigest());
        }
        return payload;
    }

    private Map<String, Object> mergePayload(ResearchAgentCompletionReceipt.MergeReceipt receipt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cell_key", receipt.cellKey());
        result.put("from_version", receipt.fromVersion());
        result.put("to_version", receipt.toVersion());
        result.put("decision", receipt.decision());
        result.put("reason_code", receipt.reasonCode());
        return result;
    }
}
