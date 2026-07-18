package com.noteweave.answer.strategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EvidenceBudgeter {

    public List<EvidenceBundle.Evidence> limitCandidates(
            List<EvidenceBundle.Evidence> evidence,
            int candidateLimit
    ) {
        if (evidence == null || evidence.isEmpty() || candidateLimit <= 0) {
            return List.of();
        }
        return evidence.stream().limit(candidateLimit).toList();
    }

    public List<EvidenceBundle.Evidence> select(
            List<EvidenceBundle.Evidence> candidates,
            RetrievalPlan.Budget budget
    ) {
        if (candidates == null || candidates.isEmpty()
                || budget == null
                || budget.maxEvidence() <= 0
                || budget.maxEvidenceCharacters() <= 0) {
            return List.of();
        }
        List<EvidenceBundle.Evidence> ranked = candidates.stream()
                .sorted(Comparator.comparingDouble(EvidenceBundle.Evidence::fusedScore).reversed())
                .toList();
        List<EvidenceBundle.Evidence> selected = new ArrayList<>();
        long characterCost = 0;
        for (EvidenceBundle.Evidence evidence : ranked) {
            if (selected.size() >= budget.maxEvidence()) {
                break;
            }
            int nextCost = Math.max(0, evidence.characterCost());
            if (characterCost + nextCost > budget.maxEvidenceCharacters()) {
                continue;
            }
            selected.add(evidence);
            characterCost += nextCost;
        }
        return List.copyOf(selected);
    }
}
