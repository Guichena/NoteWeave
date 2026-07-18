package com.noteweave.research;

import java.util.List;

public record ResearchRecoveryTargetsResponse(
        List<String> requirementIds,
        List<String> requirementTypes,
        List<String> requirementLabels,
        List<String> targetColumns,
        List<String> targetQueries,
        List<String> targetSources,
        int requirementCount,
        int queryCount,
        int sourceCount,
        int columnCount
) {
}
