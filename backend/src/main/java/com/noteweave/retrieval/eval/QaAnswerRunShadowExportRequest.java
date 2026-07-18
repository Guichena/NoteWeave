package com.noteweave.retrieval.eval;

import java.util.List;

/** Identifies completed QA AnswerRuns that should be exported as one production shadow snapshot. */
public record QaAnswerRunShadowExportRequest(
        String schemaVersion,
        String snapshotVersion,
        String strategyProfile,
        List<CaseRequest> cases
) {
    public static final String SCHEMA_VERSION = "qa-answer-run-shadow-export-request-v2";

    public QaAnswerRunShadowExportRequest {
        schemaVersion = text(schemaVersion);
        snapshotVersion = text(snapshotVersion);
        strategyProfile = text(strategyProfile);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record CaseRequest(String caseId, String answerRunId) {
        public CaseRequest {
            caseId = text(caseId);
            answerRunId = text(answerRunId);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
