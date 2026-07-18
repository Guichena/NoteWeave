package com.noteweave.answer.strategy;

import java.util.List;
import java.util.Map;

public record RetrievalPlan(
        String version,
        AnswerMode mode,
        List<Step> steps,
        Budget budget
) {
    public RetrievalPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        budget = budget == null ? new Budget(12, 8_000, 1, 30) : budget;
    }

    public record Step(
            String channel,
            int candidateLimit,
            double weight,
            Map<String, String> filters
    ) {
        public Step {
            filters = filters == null ? Map.of() : Map.copyOf(filters);
        }
    }

    public record Budget(
            int maxEvidence,
            int maxEvidenceCharacters,
            int maxGraphHops,
            int maxGraphNodes,
            int maxGraphEdges,
            int maxGraphCharacters
    ) {
        public Budget(
                int maxEvidence,
                int maxEvidenceCharacters,
                int maxGraphHops,
                int maxGraphNodes
        ) {
            this(maxEvidence, maxEvidenceCharacters, maxGraphHops, maxGraphNodes, 60, 4_000);
        }
    }
}
