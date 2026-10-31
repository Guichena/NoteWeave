package com.noteweave.answer.strategy;

import com.noteweave.common.BusinessException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class EvidenceScopeGuard {

    public void requireAllowed(
            AnswerContext context,
            RetrievalPlan plan,
            List<EvidenceBundle.Evidence> evidence
    ) {
        if (context == null || plan == null || evidence == null) {
            throw violation();
        }
        if (!context.sourceScope().isEmpty() && plan.mode() != AnswerMode.QA) {
            throw violation();
        }
        for (EvidenceBundle.Evidence item : evidence) {
            if (item == null) {
                throw violation();
            }
            if ("PASSAGE".equals(item.kind())) {
                requirePassage(context, item);
                continue;
            }
            if ("KNOWLEDGE_VERSION".equals(item.kind())) {
                requireKnowledge(item);
                continue;
            }
            if (ResearchReportEvidence.KIND.equals(item.kind())) {
                requireResearchEvidence(context, item);
                continue;
            }
            throw violation();
        }
    }

    // 研究证据只能来自本轮声明的研究报告，且必须带证据键
    private void requireResearchEvidence(AnswerContext context, EvidenceBundle.Evidence evidence) {
        String researchRunId = ResearchReportEvidence.researchRunId(evidence);
        if (isBlank(researchRunId)
                || isBlank(evidence.passageId())
                || !context.reportScope().contains(researchRunId)
                || !ResearchReportEvidence.evidenceId(researchRunId, evidence.passageId())
                .equals(evidence.evidenceId())) {
            throw violation();
        }
    }

    private void requirePassage(
            AnswerContext context,
            EvidenceBundle.Evidence evidence
    ) {
        if (isBlank(evidence.sourceId())
                || isBlank(evidence.sourceSnapshotId())
                || isBlank(evidence.passageId())
                || !("workspace-source:" + evidence.sourceId()).equals(evidence.accessScope())) {
            throw violation();
        }
        if (!context.sourceScope().isEmpty()
                && !context.sourceScope().contains(evidence.sourceId())) {
            throw violation();
        }
    }

    private void requireKnowledge(EvidenceBundle.Evidence evidence) {
        if (isBlank(evidence.knowledgeItemId())
                || isBlank(evidence.knowledgeVersionId())
                || !("workspace-knowledge:" + evidence.knowledgeItemId())
                .equals(evidence.accessScope())) {
            throw violation();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private BusinessException violation() {
        return new BusinessException(
                "EVIDENCE_SCOPE_VIOLATION",
                "检索证据未通过工作台或显式来源范围校验",
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }
}
