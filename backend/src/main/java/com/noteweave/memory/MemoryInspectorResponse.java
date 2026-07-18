package com.noteweave.memory;

import java.util.List;

public record MemoryInspectorResponse(
        MemoryRuntimePack activeMemory,
        List<MemoryRuntimeReviewItemResponse> reviewQueue
) {
}
