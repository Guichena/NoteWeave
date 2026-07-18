package com.noteweave.retrieval.eval;

import java.util.List;

public record RetrievalGoldSet(
        String schemaVersion,
        String datasetVersion,
        List<GoldCase> cases
) {
    public RetrievalGoldSet {
        schemaVersion = text(schemaVersion);
        datasetVersion = text(datasetVersion);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record GoldCase(
            String id,
            String mode,
            String workspaceId,
            String query,
            List<String> allowedSourceIds,
            List<Candidate> candidates,
            List<String> relevantEvidenceIds,
            List<String> expectedCitationIds,
            boolean shouldRefuse,
            int topK
    ) {
        public GoldCase {
            id = text(id);
            mode = text(mode);
            workspaceId = text(workspaceId);
            query = text(query);
            allowedSourceIds = copy(allowedSourceIds);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            relevantEvidenceIds = copy(relevantEvidenceIds);
            expectedCitationIds = copy(expectedCitationIds);
        }
    }

    public record Candidate(
            String evidenceId,
            String sourceId,
            String title,
            String content,
            String sourceType,
            List<String> citationIds
    ) {
        public Candidate {
            evidenceId = text(evidenceId);
            sourceId = text(sourceId);
            title = text(title);
            content = text(content);
            sourceType = text(sourceType);
            citationIds = copy(citationIds);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
