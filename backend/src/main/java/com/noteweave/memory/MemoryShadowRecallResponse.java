package com.noteweave.memory;

public record MemoryShadowRecallResponse(
        boolean shadowMode,
        MemoryControlPackResponse legacyPack,
        MemoryRuntimePack runtime
) {
}
