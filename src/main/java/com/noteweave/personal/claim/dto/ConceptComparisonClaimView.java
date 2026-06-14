package com.noteweave.personal.claim.dto;

import com.noteweave.personal.claim.model.ClaimStance;
import com.noteweave.personal.claim.model.ClaimType;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

/**
 * One claim that references the compared concept, flattened for the comparison view.
 */
@Getter
@Builder
public class ConceptComparisonClaimView {
    private Long claimId;
    private String statement;
    private ClaimType claimType;
    private ClaimStance stance;
    private BigDecimal confidence;
    /** SUPPORTS / CONTRADICTS / RELATED — how this claim uses the concept. */
    private String relationType;
    private String relationEvidence;
}
