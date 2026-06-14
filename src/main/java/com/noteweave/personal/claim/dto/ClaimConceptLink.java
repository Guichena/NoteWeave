package com.noteweave.personal.claim.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ClaimConceptLink {

    @NotNull
    private Long conceptCardId;

    /**
     * SUPPORTS / CONTRADICTS / RELATED. Defaults to RELATED when omitted.
     */
    @Size(max = 32)
    private String relationType;

    private String evidence;
}
