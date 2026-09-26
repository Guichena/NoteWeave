package com.noteweave.research;

import java.util.List;
import java.util.Map;

/** Persisted projections used to assemble one research closed-loop read model. */
record ResearchClosedLoopData(
        List<Map<String, Object>> branches,
        List<Map<String, Object>> rows,
        List<Map<String, Object>> cells,
        List<Map<String, Object>> sourceEvidence,
        List<Map<String, Object>> verifierDecisions,
        List<Map<String, Object>> checkpoints,
        List<Map<String, Object>> cellEvidence
) {
    static ResearchClosedLoopData empty() {
        return new ResearchClosedLoopData(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }
}
