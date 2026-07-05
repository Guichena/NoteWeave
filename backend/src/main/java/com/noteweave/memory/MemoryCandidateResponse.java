package com.noteweave.memory;

import java.util.List;

public record MemoryCandidateResponse(
        String candidateId,
        String workspaceId,
        String candidateType,
        String normalizedStatement,
        List<String> taskNeighborhoods,
        String evidenceGateStatus,
        double noveltyScore,
        double marginalUtilityScore,
        boolean negativeMemory,
        String conflictStatus,
        String stalenessStatus,
        String reviewStatus
) {
}
