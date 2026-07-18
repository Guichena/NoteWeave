package com.noteweave.memory;

import java.util.List;

public record MemoryOutcomeBatchResponse(
        String targetType,
        String targetId,
        String policyVersion,
        List<MemoryApplicationOutcomeResponse> outcomes
) {
}
