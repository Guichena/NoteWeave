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
public class UpdateClaimRequest {

    @NotBlank
    @Size(max = 2048)
    private String statement;

    @NotNull
    private ClaimType claimType;

    @NotNull
    private ClaimStance stance;

    private BigDecimal confidence;

    private String rationale;

    private Long supersedesClaimId;

    /**
     * When non-null, the claim's concept links are fully replaced with this set.
     * When null, existing links are left untouched.
     */
    @Valid
    private List<ClaimConceptLink> conceptLinks;

    /**
     * When non-null, the claim's evidence citations are fully replaced with this set.
     * When null, existing citations are left untouched.
     */
    private List<Long> citationIds;
}
