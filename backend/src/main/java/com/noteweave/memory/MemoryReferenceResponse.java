package com.noteweave.memory;

import com.fasterxml.jackson.annotation.JsonInclude;

public record MemoryReferenceResponse(
        String memoryObjectId,
        String memoryVersionId,
        double utilityScore,
        int scopePriority,
        int neighborhoodPriority,
        String selectionReason,
        String memoryClass,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double relevance,
        @JsonInclude(JsonInclude.Include.NON_NULL) String displayText
) {
    public MemoryReferenceResponse(
            String memoryObjectId,
            String memoryVersionId,
            double utilityScore,
            int scopePriority,
            int neighborhoodPriority,
            String selectionReason
    ) {
        this(memoryObjectId, memoryVersionId, utilityScore, scopePriority, neighborhoodPriority,
                selectionReason, null, null, null);
    }

    public MemoryReferenceResponse(
            String memoryObjectId,
            String memoryVersionId,
            double utilityScore
    ) {
        this(memoryObjectId, memoryVersionId, utilityScore, 1, 3, "legacy-order");
    }
}
