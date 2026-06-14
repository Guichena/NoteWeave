package com.noteweave.personal.claim.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * Cross-question comparison for a single concept: the concept itself plus every research question
 * whose claims reference it, grouped so conflicting conclusions across questions become visible.
 * {@code hasConflict} is true when at least two referencing claims take opposing stances
 * (SUPPORTED vs REFUTED) or are linked via opposing relation types (SUPPORTS vs CONTRADICTS).
 */
@Getter
@Builder
public class ConceptComparisonResponse {
    private Long conceptCardId;
    private String conceptName;
    private Long researchProjectId;
    private int questionCount;
    private int claimCount;
    private boolean hasConflict;
    private List<ConceptComparisonQuestionGroup> questionGroups;
}
