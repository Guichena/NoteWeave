package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.noteweave.retrieval.eval.DeterministicBm25Baseline.RankedEvidence;
import java.util.List;

public record RetrievalShadowSnapshot(
        String schemaVersion,
        String snapshotVersion,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String strategyProfile,
        List<CaseRanking> cases
) {
    public RetrievalShadowSnapshot {
        schemaVersion = text(schemaVersion);
        snapshotVersion = text(snapshotVersion);
        strategyProfile = text(strategyProfile);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public RetrievalShadowSnapshot(
            String schemaVersion,
            String snapshotVersion,
            List<CaseRanking> cases
    ) {
        this(schemaVersion, snapshotVersion, "", cases);
    }

    public record CaseRanking(
            String caseId,
            long latencyMicros,
            int candidateCount,
            List<RankedEvidence> rankedEvidence
    ) {
        public CaseRanking {
            caseId = text(caseId);
            rankedEvidence = rankedEvidence == null ? List.of() : List.copyOf(rankedEvidence);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
