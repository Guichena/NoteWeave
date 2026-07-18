package com.noteweave.artifact;

import java.util.List;
import java.util.Map;

public record ArtifactRepairSummaryTraceResponse(
        int totalRepairCount,
        int localRepairCount,
        int nodeRepairCount,
        List<String> affectedSections,
        List<String> affectedNodes,
        List<String> localRepairChecks,
        List<String> nodeRepairActions,
        Map<String, Integer> categoryCounts,
        List<String> notes
) {
}
