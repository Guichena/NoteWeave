package com.noteweave.artifact;

import java.util.List;

public record ArtifactVerificationTraceResponse(
        String status,
        List<String> passedChecks,
        List<String> repairedChecks,
        List<String> failedChecks,
        List<String> warnings
) {
}
