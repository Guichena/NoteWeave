package com.noteweave.memory;

import java.util.List;

public record MemoryControlPackResponse(
        String packType,
        String targetKey,
        String taskNeighborhood,
        List<String> styleConstraints,
        List<String> structureConstraints,
        List<String> terminologyPolicy,
        List<String> forbiddenPatterns,
        List<String> evidencePolicy,
        List<String> interactionPolicy,
        List<String> reviewChecklist,
        List<String> memoryObjectIds
) {

    public boolean hasControls() {
        return !memoryObjectIds.isEmpty();
    }
}
