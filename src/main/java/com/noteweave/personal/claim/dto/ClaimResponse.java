package com.noteweave.personal.claim.dto;

import com.noteweave.personal.card.model.PersonalCardStatus;
import com.noteweave.personal.claim.model.ClaimStance;
import com.noteweave.personal.claim.model.ClaimType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ClaimResponse {
    private Long id;
    private Long spaceId;
    private Long userId;
    private Long researchProjectId;
    private Long researchQuestionId;
    private String statement;
    private ClaimType claimType;
    private ClaimStance stance;
    private BigDecimal confidence;
    private String rationale;
    private Long supersedesClaimId;
    private PersonalCardStatus cardStatus;
    private List<ClaimConceptLinkResponse> conceptLinks;
    private List<Long> citationIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
