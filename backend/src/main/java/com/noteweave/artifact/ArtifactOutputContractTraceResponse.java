package com.noteweave.artifact;

import java.util.List;

public record ArtifactOutputContractTraceResponse(
        String status,
        List<ArtifactContractCheckTraceResponse> outlineChecks,
        List<ArtifactContractCheckTraceResponse> phraseChecks,
        List<ArtifactContractCheckTraceResponse> contractChecks,
        List<ArtifactContractCheckTraceResponse> evidenceChecks,
        ArtifactRepairSummaryTraceResponse repairSummary,
        List<String> repairedChecks,
        List<String> passedChecks,
        List<String> failedChecks,
        List<String> warnings,
        List<String> notes
) {
}
