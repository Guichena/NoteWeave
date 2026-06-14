package com.noteweave.personal.question.dto;

import com.noteweave.personal.card.dto.CardCitationResponse;
import java.util.List;
import lombok.Builder;

@Builder
public record ResearchQuestionOverviewEvidenceResponse(
        Long claimId,
        String claimStatement,
        List<CardCitationResponse> citations
) {
}
