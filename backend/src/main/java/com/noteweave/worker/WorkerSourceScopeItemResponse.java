package com.noteweave.worker;

public record WorkerSourceScopeItemResponse(
        String sourceId,
        String title,
        String summary,
        String sampleText
) {
}
