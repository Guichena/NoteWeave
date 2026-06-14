package com.noteweave.personal.claim.dto;

import com.noteweave.personal.claim.model.ClaimStance;
import com.noteweave.personal.claim.model.ClaimType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateClaimRequest {

    @NotNull
    private Long researchQuestionId;

    @NotBlank
    @Size(max = 2048)
    private String statement;

    @NotNull
    private ClaimType claimType;

    @NotNull
    private ClaimStance stance;

    /**
     * 0.0000 - 1.0000. Defaults to 0.5 when omitted.
     */
    private BigDecimal confidence;

    private String rationale;

    /**
     * Optional id of an earlier claim this one supersedes (for "current conclusion" tracking).
     */
    private Long supersedesClaimId;

    @Valid
    private List<ClaimConceptLink> conceptLinks;

    private List<Long> citationIds;
}
