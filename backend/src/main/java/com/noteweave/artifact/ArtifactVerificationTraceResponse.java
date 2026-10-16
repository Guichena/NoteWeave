package com.noteweave.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ArtifactVerificationTraceResponse(
        String status,
        List<String> passedChecks,
        List<String> repairedChecks,
        List<String> failedChecks,
        List<String> warnings,
        String contractStatus,
        List<ArtifactRepairRoundResponse> repairRounds,
        Double citationRecall,
        Double citationPrecision,
        List<Map<String, Object>> sections,
        String evidenceStatus,
        Integer llmCalls,
        String statementVerificationVersion,
        String verificationMode
) {
    public ArtifactVerificationTraceResponse {
        passedChecks = passedChecks == null ? List.of() : List.copyOf(passedChecks);
        repairedChecks = repairedChecks == null ? List.of() : List.copyOf(repairedChecks);
        failedChecks = failedChecks == null ? List.of() : List.copyOf(failedChecks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        repairRounds = repairRounds == null ? List.of() : List.copyOf(repairRounds);
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    public ArtifactVerificationTraceResponse(String status, List<String> passedChecks,
            List<String> repairedChecks, List<String> failedChecks, List<String> warnings) {
        this(status, passedChecks, repairedChecks, failedChecks, warnings,
                null, List.of(), null, null, List.of(), null, null, null, null);
    }
}
