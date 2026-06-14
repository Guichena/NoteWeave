package com.noteweave.personal.question.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record ResearchQuestionOverviewSnapshotResponse(
        List<Long> claimIds,
        List<Long> conceptCardIds,
        List<Long> citationIds,
        List<Long> sessionSummaryIds
) {
}
