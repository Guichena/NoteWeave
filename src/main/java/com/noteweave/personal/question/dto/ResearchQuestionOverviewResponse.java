package com.noteweave.personal.question.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record ResearchQuestionOverviewResponse(
        Long id,
        Long spaceId,
        Long userId,
        Long researchProjectId,
        Long researchQuestionId,
        String title,
        String summary,
        String currentAnswer,
        List<ResearchQuestionOverviewClaimResponse> keyClaims,
        List<ResearchQuestionOverviewEvidenceResponse> supportingEvidence,
        List<String> conflicts,
        List<String> openIssues,
        List<String> nextSteps,
        List<String> relatedConcepts,
        String markdown,
        ResearchQuestionOverviewSnapshotResponse generatedFromSnapshot,
        boolean generated,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
