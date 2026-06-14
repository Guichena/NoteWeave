package com.noteweave.personal.question.dto;

import com.noteweave.personal.claim.model.ClaimStance;
import com.noteweave.personal.claim.model.ClaimType;
import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

@Builder
public record ResearchQuestionOverviewClaimResponse(
        Long claimId,
        String statement,
        ClaimType claimType,
        ClaimStance stance,
        BigDecimal confidence,
        String rationale,
        List<String> conceptNames,
        List<Long> citationIds
) {
}
