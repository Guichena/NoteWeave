package com.noteweave.memory;

import java.util.List;

public record MemoryPromotionResponse(
        List<MemoryCandidateResponse> candidates,
        List<MemoryObjectResponse> memoryObjects
) {
}
