package com.noteweave.personal.claim.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * The claims about one concept that live inside a single research question. Grouping by question is
 * what surfaces "the same concept plays a different role / reaches a different conclusion here".
 */
@Getter
@Builder
public class ConceptComparisonQuestionGroup {
    private Long researchQuestionId;
    private String researchQuestionTitle;
    private List<ConceptComparisonClaimView> claims;
}
