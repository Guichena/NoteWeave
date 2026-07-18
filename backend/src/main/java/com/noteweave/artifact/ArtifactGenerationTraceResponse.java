package com.noteweave.artifact;

public record ArtifactGenerationTraceResponse(
        String mode,
        String provider,
        String model,
        boolean attempted,
        boolean applied,
        String fallbackReason,
        int generatedSectionCount,
        int sourceCount
) {
}
