package com.noteweave.memory;

public record MemoryReferenceResponse(
        String memoryObjectId,
        String memoryVersionId,
        double utilityScore,
        int scopePriority,
        int neighborhoodPriority,
        String selectionReason
) {
    public MemoryReferenceResponse(
            String memoryObjectId,
            String memoryVersionId,
            double utilityScore
    ) {
        this(memoryObjectId, memoryVersionId, utilityScore, 1, 3, "legacy-order");
    }
}
