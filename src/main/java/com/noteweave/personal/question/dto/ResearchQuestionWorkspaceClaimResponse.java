package com.noteweave.personal.question.dto;

import com.noteweave.personal.card.model.PersonalCardStatus;
import com.noteweave.personal.claim.model.ClaimStance;
import com.noteweave.personal.claim.model.ClaimType;
import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

@Builder
public record ResearchQuestionWorkspaceClaimResponse(
        Long claimId,
        String statement,
        ClaimType claimType,
        ClaimStance stance,
        BigDecimal confidence,
        String rationale,
        PersonalCardStatus cardStatus,
        List<String> conceptNames,
        List<Long> citationIds
) {
}
