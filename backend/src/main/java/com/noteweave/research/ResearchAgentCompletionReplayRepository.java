package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads immutable completion anchors and performs full-content replay checks. */
@Component
class ResearchAgentCompletionReplayRepository {
    private static final String RECEIPT_DIGEST_DOMAIN = "research-agent-completion-receipt.v1";
    private static final Set<String> RECEIPT_FIELDS = Set.of(
            "schema_version", "completion_id", "execution_id", "task_id", "completion_digest", "receipt_digest",
            "outcome", "evidence_appended", "candidate_count", "accepted_merges", "rejected_merges", "budget"
    );
    private static final Set<String> MERGE_FIELDS = Set.of(
            "cell_key", "from_version", "to_version", "decision", "reason_code"
    );
    private static final Set<String> BUDGET_FIELDS = Set.of("state", "reserved", "consumed", "released");
    private static final Set<String> COMPLETE_BUDGET_KEYS = Set.of(
            "llm_calls", "search_calls", "fetch_calls", "read_calls", "extract_calls",
            "evidence_cards", "candidates_submitted", "evidence_appended",
            "candidate_merges_accepted", "candidate_merges_rejected"
    );
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentCompletionCanonicalizer canonicalizer;

    ResearchAgentCompletionReplayRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentCompletionCanonicalizer canonicalizer
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
    }

    CompletionRow findByTask(String taskId, boolean forUpdate) {
        return jdbcTemplate.query("""
                select id, research_agent_task_id, execution_id, completion_key, schema_version,
                       envelope_digest, envelope_json, envelope_size_bytes, snapshot_digest,
                       worker_instance_id, lease_epoch, fencing_token, receipt_json, receipt_digest
                from research_agent_completion where research_agent_task_id = ?
                """ + (forUpdate ? " for update" : ""), rs -> rs.next() ? new CompletionRow(
                rs.getString("id"), rs.getString("research_agent_task_id"), rs.getString("execution_id"),
                rs.getString("completion_key"), rs.getString("schema_version"), rs.getString("envelope_digest"),
                rs.getString("envelope_json"), rs.getInt("envelope_size_bytes"), rs.getString("snapshot_digest"),
                rs.getString("worker_instance_id"), rs.getInt("lease_epoch"), rs.getLong("fencing_token"),
                rs.getString("receipt_json"), rs.getString("receipt_digest")) : null, taskId);
    }

    ResearchAgentCompletionReceipt resolve(
            CompletionRow row,
            ResearchAgentCompletionCanonicalizer.ValidatedEnvelope validated
    ) {
        ResearchAgentCompletionEnvelope envelope = validated.envelope();
        if (!row.completionKey().equals(envelope.executionKey())) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_ALREADY_COMMITTED",
                    "Research agent task already has a different committed completion");
        }
        int canonicalBytes = validated.canonicalJson().getBytes(StandardCharsets.UTF_8).length;
        boolean exact = row.taskId().equals(envelope.taskId())
                && row.schemaVersion().equals(envelope.schemaVersion())
                && row.envelopeDigest().equals(validated.digest())
                && row.envelopeJson().equals(validated.canonicalJson())
                && row.envelopeSizeBytes() == canonicalBytes
                && row.snapshotDigest().equals(envelope.taskSnapshotDigest())
                && row.workerInstanceId().equals(envelope.workerInstanceId())
                && row.leaseEpoch() == envelope.leaseEpoch()
                && row.fencingToken() == envelope.fencingToken();
        if (!exact) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_IDEMPOTENCY_CONFLICT",
                    "Completion key is bound to different identity or canonical content");
        }
        try {
            Map<String, Object> storedPayload = objectMapper.readValue(
                    row.receiptJson(), new TypeReference<LinkedHashMap<String, Object>>() { });
            if (!storedPayload.keySet().equals(RECEIPT_FIELDS)
                    || storedPayload.containsKey("idempotent_replay")
                    || !(storedPayload.get("receipt_digest") instanceof String embeddedDigest)
                    || !MessageDigest.isEqual(row.receiptDigest().getBytes(StandardCharsets.US_ASCII),
                    embeddedDigest.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalStateException("Committed research-agent receipt metadata is inconsistent");
            }
            ReceiptBudget receiptBudget = validateNestedReceipt(storedPayload, envelope);
            storedPayload.remove("receipt_digest");
            String recomputed = canonicalizer.domainSeparatedDigest(RECEIPT_DIGEST_DOMAIN, storedPayload);
            if (!MessageDigest.isEqual(row.receiptDigest().getBytes(StandardCharsets.US_ASCII),
                    recomputed.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalStateException("Committed research-agent receipt digest is invalid");
            }
            storedPayload.put("receipt_digest", row.receiptDigest());
            if (!row.receiptJson().equals(canonicalizer.canonicalJsonValue(storedPayload))) {
                throw new IllegalStateException("Committed research-agent receipt is not canonical JSON");
            }
            ResearchAgentCompletionReceipt stored = objectMapper.readValue(
                    row.receiptJson(), ResearchAgentCompletionReceipt.class);
            if (!"research-agent-completion-receipt.v1".equals(stored.schemaVersion())
                    || !row.id().equals(stored.completionId()) || !row.executionId().equals(stored.executionId())
                    || !row.taskId().equals(stored.taskId()) || !row.envelopeDigest().equals(stored.completionDigest())
                    || !row.receiptDigest().equals(stored.receiptDigest())) {
                throw new IllegalStateException("Committed research-agent receipt does not match its aggregate anchor");
            }
            validatePhysicalBudgetReservation(row, receiptBudget);
            return stored.withIdempotentReplay(true);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Committed research-agent receipt is unreadable", exception);
        }
    }

    private ReceiptBudget validateNestedReceipt(
            Map<String, Object> payload,
            ResearchAgentCompletionEnvelope envelope
    ) {
        int evidenceCount = nonNegativeInt(payload.get("evidence_appended"), "evidence_appended");
        int candidateCount = nonNegativeInt(payload.get("candidate_count"), "candidate_count");
        if (evidenceCount > ResearchAgentCompletionCanonicalizer.MAX_EVIDENCE
                || candidateCount > ResearchAgentCompletionCanonicalizer.MAX_CANDIDATES) {
            throw corrupt("Committed research-agent receipt counts exceed the completion contract");
        }
        Set<String> mergeCells = new HashSet<>();
        Map<String, StoredMerge> storedMerges = new LinkedHashMap<>();
        int accepted = validateMergeList(payload.get("accepted_merges"), "ACCEPTED", mergeCells, storedMerges);
        int rejected = validateMergeList(payload.get("rejected_merges"), "REJECTED", mergeCells, storedMerges);
        String outcome = payload.get("outcome") instanceof String value ? value : "";
        boolean quorumPending = "QUORUM_PENDING".equals(outcome);
        if ((!quorumPending && candidateCount != accepted + rejected)
                || (quorumPending && (candidateCount < 1 || accepted != 0 || rejected != 0))) {
            throw corrupt("Committed research-agent receipt candidate count is inconsistent");
        }
        if (evidenceCount != envelope.evidence().size() || candidateCount != envelope.candidates().size()) {
            throw corrupt("Committed research-agent receipt counts disagree with its completion envelope");
        }

        if (!(payload.get("budget") instanceof Map<?, ?> budget) || !budget.keySet().equals(BUDGET_FIELDS)
                || !"SETTLED".equals(budget.get("state"))) {
            throw corrupt("Committed research-agent receipt budget shape is invalid");
        }
        Map<String, Long> reserved = exactBudgetMap(budget.get("reserved"), "reserved");
        Map<String, Long> consumed = exactBudgetMap(budget.get("consumed"), "consumed");
        Map<String, Long> released = exactBudgetMap(budget.get("released"), "released");
        for (String key : COMPLETE_BUDGET_KEYS) {
            long conserved;
            try {
                conserved = Math.addExact(consumed.get(key), released.get(key));
            } catch (ArithmeticException overflow) {
                throw corrupt("Committed research-agent receipt budget conservation overflowed");
            }
            if (reserved.get(key) != conserved) {
                throw corrupt("Committed research-agent receipt budget is not conserved");
            }
        }
        if (consumed.get("evidence_appended") != evidenceCount
                || consumed.get("candidate_merges_accepted") != accepted
                || consumed.get("candidate_merges_rejected") != rejected
                || consumed.get("candidates_submitted") != candidateCount) {
            throw corrupt("Committed research-agent receipt budget counters disagree with its outcomes");
        }
        for (String key : ResearchAgentCompletionCanonicalizer.WORKER_USAGE_KEYS) {
            if (!consumed.get(key).equals(envelope.budgetUsage().get(key))) {
                throw corrupt("Committed research-agent receipt usage disagrees with its completion envelope");
            }
        }
        if ("QUORUM_MERGED".equals(outcome)) {
            if (accepted != 1 || rejected != 0 || storedMerges.values().stream().noneMatch(merge ->
                    "QUORUM_VERIFIED_AND_VERSION_MATCHED".equals(merge.reasonCode()))) {
                throw corrupt("Committed research-agent quorum merge outcome is invalid");
            }
        } else if ("QUORUM_REPAIR_REQUIRED".equals(outcome)) {
            if (accepted != 0 || rejected != 1) {
                throw corrupt("Committed research-agent quorum repair outcome is invalid");
            }
        } else if (!quorumPending) {
            validateMergeOutcomes(envelope, storedMerges);
        }
        return new ReceiptBudget(reserved, consumed, released);
    }

    private void validatePhysicalBudgetReservation(CompletionRow anchor, ReceiptBudget receipt) {
        List<BudgetReservationAnchor> reservations = jdbcTemplate.query("""
                select research_run_id, research_agent_task_id, state, agent_completion_id, settlement_key,
                       reserved_json, consumed_json, released_json,
                       settled_at, finalized_at
                from research_budget_reservation where research_agent_task_id = ?
                """, (rs, rowNum) -> new BudgetReservationAnchor(
                rs.getString("research_run_id"), rs.getString("research_agent_task_id"), rs.getString("state"),
                rs.getString("agent_completion_id"), rs.getString("settlement_key"),
                rs.getString("reserved_json"), rs.getString("consumed_json"), rs.getString("released_json"),
                rs.getTimestamp("settled_at") != null, rs.getTimestamp("finalized_at") != null),
                anchor.taskId());
        if (reservations.size() != 1) {
            throw corrupt("Committed research-agent budget reservation anchor is missing or duplicated");
        }
        BudgetReservationAnchor reservation = reservations.get(0);
        String taskRunId = jdbcTemplate.query("""
                select research_run_id from research_agent_task where id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, anchor.taskId());
        if (!anchor.taskId().equals(reservation.taskId())
                || taskRunId == null || !taskRunId.equals(reservation.runId())
                || !anchor.id().equals(reservation.completionId())
                || !anchor.completionKey().equals(reservation.settlementKey())
                || !"SETTLED".equals(reservation.state())
                || !reservation.settled() || !reservation.finalized()) {
            throw corrupt("Committed research-agent budget reservation identity is inconsistent");
        }
        Map<String, Long> reserved = readExactBudgetJson(reservation.reservedJson(), "reserved");
        Map<String, Long> consumed = readExactBudgetJson(reservation.consumedJson(), "consumed");
        Map<String, Long> released = readExactBudgetJson(reservation.releasedJson(), "released");
        validateConservation(reserved, consumed, released, "physical budget reservation");
        if (!reserved.equals(receipt.reserved()) || !consumed.equals(receipt.consumed())
                || !released.equals(receipt.released())) {
            throw corrupt("Committed research-agent budget reservation disagrees with its receipt");
        }
    }

    private Map<String, Long> readExactBudgetJson(String json, String field) {
        try {
            Map<String, Object> raw = objectMapper.readValue(
                    json, new TypeReference<LinkedHashMap<String, Object>>() { });
            return exactBudgetMap(raw, "physical " + field);
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw corrupt("Committed research-agent budget reservation " + field + " JSON is invalid");
        }
    }

    private void validateConservation(
            Map<String, Long> reserved,
            Map<String, Long> consumed,
            Map<String, Long> released,
            String scope
    ) {
        for (String key : COMPLETE_BUDGET_KEYS) {
            final long sum;
            try {
                sum = Math.addExact(consumed.get(key), released.get(key));
            } catch (ArithmeticException overflow) {
                throw corrupt("Committed research-agent " + scope + " conservation overflowed");
            }
            if (!Objects.equals(reserved.get(key), sum)) {
                throw corrupt("Committed research-agent " + scope + " is not conserved");
            }
        }
    }

    private int validateMergeList(
            Object raw,
            String expectedDecision,
            Set<String> cells,
            Map<String, StoredMerge> storedMerges
    ) {
        if (!(raw instanceof List<?> merges)) {
            throw corrupt("Committed research-agent receipt merge list is invalid");
        }
        for (Object item : merges) {
            if (!(item instanceof Map<?, ?> merge) || !merge.keySet().equals(MERGE_FIELDS)
                    || !(merge.get("cell_key") instanceof String cellKey) || cellKey.isBlank()
                    || !cells.add(cellKey)
                    || !(merge.get("decision") instanceof String decision) || !expectedDecision.equals(decision)
                    || !(merge.get("reason_code") instanceof String reason) || reason.isBlank()) {
                throw corrupt("Committed research-agent receipt merge shape is invalid");
            }
            int from = nonNegativeInt(merge.get("from_version"), "from_version");
            int to = nonNegativeInt(merge.get("to_version"), "to_version");
            if ("ACCEPTED".equals(expectedDecision) && (from == Integer.MAX_VALUE || to != from + 1)) {
                throw corrupt("Committed research-agent accepted merge version transition is invalid");
            }
            if ("REJECTED".equals(expectedDecision) && to != from) {
                throw corrupt("Committed research-agent receipt rejected merge changed the cell version");
            }
            storedMerges.put(cellKey, new StoredMerge(from, to, expectedDecision, reason));
        }
        return merges.size();
    }

    private void validateMergeOutcomes(
            ResearchAgentCompletionEnvelope envelope,
            Map<String, StoredMerge> storedMerges
    ) {
        Map<String, ResearchAgentCompletionEnvelope.Evidence> evidenceByKey = new LinkedHashMap<>();
        envelope.evidence().forEach(item -> evidenceByKey.put(item.evidenceKey(), item));
        if (storedMerges.size() != envelope.candidates().size()) {
            throw corrupt("Committed research-agent receipt merge cells disagree with its completion envelope");
        }
        for (ResearchAgentCompletionEnvelope.Candidate candidate : envelope.candidates()) {
            StoredMerge merge = storedMerges.get(candidate.cellKey());
            boolean supported = candidate.evidenceKeys().stream().allMatch(key -> {
                ResearchAgentCompletionEnvelope.Evidence evidence = evidenceByKey.get(key);
                return evidence != null && "SUPPORTS".equals(evidence.relationType())
                        && evidence.quoteText() != null && !evidence.quoteText().isBlank()
                        && candidate.candidateValue().equals(evidence.claimText());
            });
            String decision = supported ? "ACCEPTED" : "REJECTED";
            String reason = supported ? "VERIFIED_AND_VERSION_MATCHED" : "NOT_ENOUGH_INFO";
            int expectedTo = candidate.baseCellVersion() + (supported ? 1 : 0);
            if (merge == null || merge.fromVersion() != candidate.baseCellVersion()
                    || merge.toVersion() != expectedTo || !decision.equals(merge.decision())
                    || !reason.equals(merge.reasonCode())) {
                throw corrupt("Committed research-agent receipt merge outcome disagrees with its completion envelope");
            }
        }
    }

    private Map<String, Long> exactBudgetMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> values) || !values.keySet().equals(COMPLETE_BUDGET_KEYS)) {
            throw corrupt("Committed research-agent receipt " + field + " budget dimensions are invalid");
        }
        Map<String, Long> result = new LinkedHashMap<>();
        for (String key : COMPLETE_BUDGET_KEYS) {
            result.put(key, nonNegativeLong(values.get(key), field + "." + key));
        }
        return Map.copyOf(result);
    }

    private int nonNegativeInt(Object raw, String field) {
        long value = nonNegativeLong(raw, field);
        if (value > Integer.MAX_VALUE) {
            throw corrupt("Committed research-agent receipt " + field + " exceeds integer range");
        }
        return (int) value;
    }

    private long nonNegativeLong(Object raw, String field) {
        final long value;
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
            value = ((Number) raw).longValue();
        } else if (raw instanceof BigInteger integer) {
            try {
                value = integer.longValueExact();
            } catch (ArithmeticException overflow) {
                throw corrupt("Committed research-agent receipt " + field + " exceeds long range");
            }
        } else {
            throw corrupt("Committed research-agent receipt " + field + " is not an integer");
        }
        if (value < 0) throw corrupt("Committed research-agent receipt " + field + " is negative");
        return value;
    }

    private IllegalStateException corrupt(String message) {
        return new IllegalStateException(message);
    }

    private record StoredMerge(int fromVersion, int toVersion, String decision, String reasonCode) { }
    private record ReceiptBudget(
            Map<String, Long> reserved,
            Map<String, Long> consumed,
            Map<String, Long> released
    ) { }
    private record BudgetReservationAnchor(
            String runId,
            String taskId,
            String state,
            String completionId,
            String settlementKey,
            String reservedJson,
            String consumedJson,
            String releasedJson,
            boolean settled,
            boolean finalized
    ) { }

    record CompletionRow(
            String id,
            String taskId,
            String executionId,
            String completionKey,
            String schemaVersion,
            String envelopeDigest,
            String envelopeJson,
            int envelopeSizeBytes,
            String snapshotDigest,
            String workerInstanceId,
            int leaseEpoch,
            long fencingToken,
            String receiptJson,
            String receiptDigest
    ) { }
}
