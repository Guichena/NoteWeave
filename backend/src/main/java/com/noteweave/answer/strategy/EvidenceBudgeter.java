package com.noteweave.answer.strategy;

import java.util.ArrayList;
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

    /**
     * 按检索计划的步骤顺序、步骤内按各链路自己确定的选择顺序消费预算，不再按 fusedScore 全局重排。
     * QA 的 MMR、NOTE 的窗口重排、WIKI 的种子与扩展顺序都由各自链路决定，公共层只做非空和资源限制；
     * 不同链路的分数量纲不同，混在一起排序没有意义。放不下的证据跳过，继续尝试后面较短的证据。
     */
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
        List<EvidenceBundle.Evidence> selected = new ArrayList<>();
        long characterCost = 0;
        for (EvidenceBundle.Evidence evidence : candidates) {
            if (selected.size() >= budget.maxEvidence()) {
                break;
            }
            // 空正文不能作为零成本成员占用名额
            if (evidence == null || evidence.excerpt() == null || evidence.excerpt().isBlank()) {
                continue;
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
