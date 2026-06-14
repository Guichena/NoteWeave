package com.noteweave.personal.question.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record ResearchQuestionWorkspaceConceptResponse(
        Long conceptCardId,
        String conceptName,
        List<String> relationTypes,
        int claimCount,
        boolean conflicting
) {
}
