package com.noteweave.memory;

import java.time.Instant;
import java.util.List;

public record MemoryVersionResponse(
        String memoryVersionId,
        String memoryObjectId,
        String workspaceId,
        int versionNo,
        String canonicalStatement,
        List<String> taskNeighborhoods,
        MemoryCompileHintsResponse compileHints,
        List<String> forbiddenPatterns,
        String status,
        String supersedesVersionId,
        Instant validFrom,
        Instant validTo,
        String createdFromCandidateId,
        String policyVersion,
        double riskScore,
        String scopeStatus
) {
}
