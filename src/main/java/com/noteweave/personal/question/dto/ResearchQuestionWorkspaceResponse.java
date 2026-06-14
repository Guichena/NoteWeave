package com.noteweave.personal.question.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record ResearchQuestionWorkspaceResponse(
        ResearchQuestionResponse question,
        List<ResearchQuestionWorkspaceClaimResponse> currentClaims,
        List<ResearchQuestionWorkspaceClaimResponse> openIssues,
        List<ResearchQuestionWorkspaceConceptResponse> relatedConcepts,
        List<ResearchQuestionWorkspaceConceptResponse> conflictingConcepts,
        List<ResearchQuestionWorkspaceSessionResponse> recentSessions,
        ResearchQuestionOverviewResponse latestOverview,
        String nextStep
) {
}
