package com.noteweave.memory;

import java.util.List;

public record MemoryRuntimePack(
        String policyVersion,
        List<MemoryReferenceResponse> memoryReferences
) {
    public static final String POLICY_VERSION = "memory-runtime-v1";

    public MemoryRuntimePack {
        memoryReferences = memoryReferences == null ? List.of() : List.copyOf(memoryReferences);
    }

    public static MemoryRuntimePack empty() {
        return new MemoryRuntimePack(POLICY_VERSION, List.of());
    }
}
