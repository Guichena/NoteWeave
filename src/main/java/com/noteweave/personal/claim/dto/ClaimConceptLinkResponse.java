package com.noteweave.personal.claim.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ClaimConceptLinkResponse {
    private Long conceptCardId;
    private String conceptName;
    private String relationType;
    private String evidence;
}
