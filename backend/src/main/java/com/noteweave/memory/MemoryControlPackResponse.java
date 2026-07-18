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
        List<String> memoryObjectIds,
        List<MemoryReferenceResponse> memoryReferences,
        MemoryCompilationTraceResponse compilationTrace
) {

    public MemoryControlPackResponse {
        memoryObjectIds = memoryObjectIds == null ? List.of() : List.copyOf(memoryObjectIds);
        memoryReferences = memoryReferences == null ? List.of() : List.copyOf(memoryReferences);
        compilationTrace = compilationTrace == null
                ? MemoryCompilationTraceResponse.empty() : compilationTrace;
    }

    public boolean hasControls() {
        return !memoryObjectIds.isEmpty();
    }
}
