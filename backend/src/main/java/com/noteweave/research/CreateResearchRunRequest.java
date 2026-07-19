package com.noteweave.research;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateResearchRunRequest(
        @NotBlank @Size(max = 8000) String question,
        @NotBlank @Size(max = 64) String profile,
        @Size(max = 2000) String researchGoal,
        @Size(max = 2000) String deliverableFormat,
        List<@NotBlank @Size(max = 256) String> constraints,
        @Size(max = 512) String timeRange,
        @Size(max = 64) String depth,
        @Size(max = 64) String researchType,
        List<@NotBlank @Size(max = 36) String> sourceScopeSourceIds,
        @Size(max = 32) String retrievalMode,
        List<@NotBlank @Size(max = 36) String> seedSourceIds
) {
    public CreateResearchRunRequest(
            String question,
            String profile,
            String researchGoal,
            String deliverableFormat,
            List<String> constraints,
            String timeRange,
            String depth,
            String researchType,
            List<String> sourceScopeSourceIds
    ) {
        this(question, profile, researchGoal, deliverableFormat, constraints, timeRange, depth,
                researchType, sourceScopeSourceIds, null, null);
    }
}
