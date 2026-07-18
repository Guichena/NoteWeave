package com.noteweave.memory;

public record MemoryApplicationOutcomeResponse(
        String applicationId,
        String memoryObjectId,
        String memoryVersionId,
        String outcomeType,
        double outcomeScore,
        double utilityBefore,
        double utilityAfter,
        int applicationCount,
        int positiveOutcomeCount,
        int negativeOutcomeCount,
        int editOutcomeCount,
        int retryOutcomeCount,
        String objectStatus,
        String reviewStatus,
        String policyVersion
) {
}
