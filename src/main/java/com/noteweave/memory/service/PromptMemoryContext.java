package com.noteweave.memory.service;

import java.util.List;

public record PromptMemoryContext(
        List<String> claimSummaries,
        List<String> recalledClaimSummaries,
        List<String> questionSummaries,
        List<String> sessionSummaries,
        List<String> spaceMemories,
        List<String> userMemories
) {
    public static PromptMemoryContext empty() {
        return new PromptMemoryContext(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return claimSummaries.isEmpty()
                && recalledClaimSummaries.isEmpty()
                && questionSummaries.isEmpty()
                && sessionSummaries.isEmpty()
                && spaceMemories.isEmpty()
                && userMemories.isEmpty();
    }
}
