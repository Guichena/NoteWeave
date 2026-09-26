package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Computes completion merge decisions without touching the database.
 *
 * <p>The committer owns transaction locks, durable CAS and merge writes. This
 * collaborator owns only the deterministic decision that is checked before
 * and after those writes.</p>
 */
@Component
class ResearchAgentCompletionMergePlanner {

    ResearchAgentCompletionCommitter.MergeOutcome planSingle(
            ResearchAgentCompletionEnvelope envelope,
            List<ResearchAgentCompletionCommitter.CellRow> cells,
            Set<String> qualificationRejectedCandidates
    ) {
        Map<String, ResearchAgentCompletionEnvelope.Evidence> evidenceByKey = new HashMap<>();
        envelope.evidence().forEach(item -> evidenceByKey.put(item.evidenceKey(), item));
        Set<String> lockedCells = new HashSet<>();
        cells.forEach(cell -> lockedCells.add(cell.cellKey()));
        List<ResearchAgentCompletionReceipt.MergeReceipt> accepted = new ArrayList<>();
        List<ResearchAgentCompletionReceipt.MergeReceipt> rejected = new ArrayList<>();
        for (ResearchAgentCompletionEnvelope.Candidate candidate : envelope.candidates().stream()
                .sorted(Comparator.comparing(
                        ResearchAgentCompletionEnvelope.Candidate::cellKey,
                        ResearchAgentBinaryOrder.UTF8)).toList()) {
            if (!lockedCells.contains(candidate.cellKey())) {
                throw new BusinessException("RESEARCH_AGENT_TASK_SNAPSHOT_STALE",
                        "Candidate target is not locked by its task snapshot");
            }
            boolean literalSupport = candidate.evidenceKeys().stream().map(evidenceByKey::get).allMatch(evidence ->
                    evidence != null && "SUPPORTS".equals(evidence.relationType())
                            && candidate.candidateValue().equals(evidence.claimText()));
            boolean qualificationRejected = qualificationRejectedCandidates.contains(candidate.candidateKey());
            boolean supported = literalSupport && !qualificationRejected;
            ResearchAgentCompletionReceipt.MergeReceipt receipt =
                    new ResearchAgentCompletionReceipt.MergeReceipt(
                            candidate.cellKey(), candidate.baseCellVersion(),
                            supported ? candidate.baseCellVersion() + 1 : candidate.baseCellVersion(),
                            supported ? "ACCEPTED" : "REJECTED",
                            supported ? "VERIFIED_AND_VERSION_MATCHED"
                                    : literalSupport ? "EVIDENCE_QUALIFICATION_REJECTED" : "NOT_ENOUGH_INFO");
            if (supported) {
                accepted.add(receipt);
            } else {
                rejected.add(receipt);
            }
        }
        return new ResearchAgentCompletionCommitter.MergeOutcome(List.copyOf(accepted), List.copyOf(rejected));
    }

    ResearchAgentCompletionCommitter.MergeOutcome planQuorum(
            ResearchAgentCompletionCommitter.TaskRow task,
            ResearchAgentCompletionEnvelope envelope,
            Map<String, ResearchAgentCompletionCommitter.TrustedEvidenceSource> trustedEvidence,
            List<ResearchAgentCompletionCommitter.QuorumCandidate> existing,
            Set<String> qualificationRejectedCandidates
    ) {
        if (existing.isEmpty()) {
            return new ResearchAgentCompletionCommitter.MergeOutcome(List.of(), List.of());
        }
        // A quorum slot may legitimately finish with evidence but no supported
        // candidate. Persist that completion as pending even when its sibling
        // already staged a candidate; retrying cannot manufacture a candidate
        // and incorrectly turns an evidence gap into an infrastructure failure.
        if (envelope.candidates().isEmpty()) {
            return new ResearchAgentCompletionCommitter.MergeOutcome(List.of(), List.of());
        }
        if (existing.size() != 1 || envelope.candidates().size() != 1) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_QUORUM_CONFLICT",
                    "Quorum group has an invalid candidate cardinality");
        }
        ResearchAgentCompletionEnvelope.Candidate candidate = envelope.candidates().get(0);
        Map<String, ResearchAgentCompletionEnvelope.Evidence> evidence = new HashMap<>();
        envelope.evidence().forEach(item -> evidence.put(item.evidenceKey(), item));
        List<String> domains = candidate.evidenceKeys().stream().map(trustedEvidence::get)
                .filter(java.util.Objects::nonNull)
                .flatMap(source -> source.quorumIdentities().stream())
                .distinct().sorted().toList();
        boolean supported = candidate.evidenceKeys().stream().map(evidence::get).allMatch(item ->
                item != null && "SUPPORTS".equals(item.relationType())
                        && candidate.candidateValue().equals(item.claimText()))
                && !qualificationRejectedCandidates.contains(candidate.candidateKey());
        ResearchAgentCompletionCommitter.QuorumCandidate current =
                new ResearchAgentCompletionCommitter.QuorumCandidate(
                        null, task.id(), null, candidate.candidateKey(), candidate.cellKey(),
                        candidate.baseCellVersion(), candidate.candidateValue(), candidate.confidencePpm(), domains,
                        candidate.evidenceKeys().stream().sorted().toList(), null, task.candidateSlot(), supported);
        List<ResearchAgentCompletionCommitter.QuorumCandidate> candidates = new ArrayList<>(existing);
        candidates.add(current);
        return quorumOutcome(candidates);
    }

    ResearchAgentCompletionCommitter.MergeOutcome quorumOutcome(
            List<ResearchAgentCompletionCommitter.QuorumCandidate> candidates
    ) {
        if (candidates.size() != 2) {
            return new ResearchAgentCompletionCommitter.MergeOutcome(List.of(), List.of());
        }
        ResearchAgentCompletionCommitter.QuorumCandidate first = candidates.get(0);
        ResearchAgentCompletionCommitter.QuorumCandidate second = candidates.get(1);
        String reason = null;
        if (first.slot() == second.slot() || first.taskId().equals(second.taskId())) {
            reason = "QUORUM_EXECUTION_NOT_INDEPENDENT";
        } else if (!first.cellKey().equals(second.cellKey())
                || first.baseCellVersion() != second.baseCellVersion()) {
            reason = "QUORUM_TARGET_CONFLICT";
        } else if (!nfc(first.value()).equals(nfc(second.value()))) {
            reason = "QUORUM_VALUE_CONFLICT";
        } else if (!first.supported() || !second.supported()
                || !hasStrongSourceIdentity(first.domains())
                || !hasStrongSourceIdentity(second.domains())) {
            reason = "QUORUM_PROVENANCE_MISSING";
        } else if (!java.util.Collections.disjoint(first.domains(), second.domains())) {
            reason = "QUORUM_SOURCE_DOMAIN_NOT_INDEPENDENT";
        }
        ResearchAgentCompletionReceipt.MergeReceipt receipt =
                new ResearchAgentCompletionReceipt.MergeReceipt(
                        first.cellKey(), first.baseCellVersion(),
                        reason == null ? first.baseCellVersion() + 1 : first.baseCellVersion(),
                        reason == null ? "ACCEPTED" : "REJECTED",
                        reason == null ? "QUORUM_VERIFIED_AND_VERSION_MATCHED" : reason);
        return reason == null
                ? new ResearchAgentCompletionCommitter.MergeOutcome(List.of(receipt), List.of())
                : new ResearchAgentCompletionCommitter.MergeOutcome(List.of(), List.of(receipt));
    }

    private boolean hasStrongSourceIdentity(List<String> identities) {
        return identities.stream().anyMatch(value -> value.startsWith("domain:"))
                && identities.stream().anyMatch(value -> value.startsWith("lineage:"));
    }

    private String nfc(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }
}
