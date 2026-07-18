package com.noteweave.answer.strategy;

import com.noteweave.common.BusinessException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AnswerStrategyContractValidator {

    public void validatePlan(
            AnswerMode expectedMode,
            AnswerContext context,
            RetrievalPlan plan
    ) {
        if (expectedMode == null || context == null || plan == null
                || isBlank(plan.version())
                || plan.mode() != expectedMode
                || plan.steps().isEmpty()
                || plan.budget() == null
                || plan.budget().maxEvidence() <= 0
                || plan.budget().maxEvidenceCharacters() <= 0
                || plan.budget().maxGraphHops() < 0
                || plan.budget().maxGraphNodes() < 0
                || plan.budget().maxGraphEdges() < 0
                || plan.budget().maxGraphCharacters() < 0) {
            throw invalidPlan();
        }
        String expectedSourceIds = context.sourceScope().stream().sorted()
                .collect(java.util.stream.Collectors.joining(","));
        boolean matchedSourceScope = context.sourceScope().isEmpty();
        for (RetrievalPlan.Step step : plan.steps()) {
            if (step == null
                    || isBlank(step.channel())
                    || step.candidateLimit() <= 0
                    || !Double.isFinite(step.weight())
                    || step.weight() <= 0
                    || !context.workspaceId().equals(step.filters().get("workspace_id"))) {
                throw invalidPlan();
            }
            String sourceIds = step.filters().get("source_ids");
            if (context.sourceScope().isEmpty()) {
                if (sourceIds != null && !sourceIds.isBlank()) {
                    throw invalidPlan();
                }
            } else if (expectedSourceIds.equals(sourceIds)) {
                matchedSourceScope = true;
            } else if (sourceIds != null && !sourceIds.isBlank()) {
                throw invalidPlan();
            }
        }
        if (!context.sourceScope().isEmpty()
                && (expectedMode != AnswerMode.QA || !matchedSourceScope)) {
            throw invalidPlan();
        }
    }

    public void validatePromptAndPolicy(
            RetrievalPlan plan,
            EvidenceBundle bundle,
            PromptSpec prompt,
            AnswerPolicy policy
    ) {
        if (plan == null || bundle == null || prompt == null || policy == null
                || isBlank(prompt.systemPrompt())
                || isBlank(prompt.userPrompt())
                || !plan.version().equals(prompt.promptVersion())
                || !plan.version().equals(bundle.retrievalPlanVersion())
                || policy.minimumEvidence() < 0
                || policy.maximumOutputTokens() <= 0) {
            throw invalidPromptPolicy();
        }
        Set<String> bundleEvidenceIds = new HashSet<>();
        for (EvidenceBundle.Evidence evidence : bundle.evidence()) {
            if (evidence == null || isBlank(evidence.evidenceId())
                    || !bundleEvidenceIds.add(evidence.evidenceId())) {
                throw invalidPromptPolicy();
            }
        }
        Set<String> referencedIds = new HashSet<>();
        for (String evidenceId : prompt.referencedEvidenceIds()) {
            if (isBlank(evidenceId)
                    || !referencedIds.add(evidenceId)
                    || !bundleEvidenceIds.contains(evidenceId)) {
                throw invalidPromptPolicy();
            }
        }
        if (prompt.refusal()) {
            if (!prompt.referencedEvidenceIds().isEmpty()) {
                throw invalidPromptPolicy();
            }
            return;
        }
        int evidenceCount = bundle.evidence().size();
        if (evidenceCount == 0 && !policy.allowNoEvidenceAnswer()) {
            throw invalidPromptPolicy();
        }
        if (evidenceCount > 0 && evidenceCount < policy.minimumEvidence()) {
            throw invalidPromptPolicy();
        }
        if (policy.citationRequired()
                && prompt.referencedEvidenceIds().size() < policy.minimumEvidence()) {
            throw invalidPromptPolicy();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private BusinessException invalidPlan() {
        return new BusinessException(
                "ANSWER_RETRIEVAL_PLAN_INVALID",
                "回答策略生成了无效的检索计划",
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }

    private BusinessException invalidPromptPolicy() {
        return new BusinessException(
                "ANSWER_PROMPT_POLICY_INVALID",
                "回答策略生成的 Prompt 或 Policy 不满足统一证据契约",
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }
}
