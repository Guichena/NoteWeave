package com.noteweave.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ArtifactRepairRoundResponse(int round, List<String> failedChecks, List<String> repairedSections,
        String status, List<String> backtrackNodes, List<String> remainingFailedChecks, List<String> validationErrors) {
    public ArtifactRepairRoundResponse {
        failedChecks = failedChecks == null ? List.of() : List.copyOf(failedChecks);
        repairedSections = repairedSections == null ? List.of() : List.copyOf(repairedSections);
        backtrackNodes = backtrackNodes == null ? List.of() : List.copyOf(backtrackNodes);
        remainingFailedChecks = remainingFailedChecks == null ? List.of() : List.copyOf(remainingFailedChecks);
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }

    public ArtifactRepairRoundResponse(int round, List<String> failedChecks, List<String> repairedSections) {
        this(round, failedChecks, repairedSections, null, List.of(), List.of(), List.of());
    }
}
