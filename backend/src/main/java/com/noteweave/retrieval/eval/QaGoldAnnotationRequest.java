package com.noteweave.retrieval.eval;

import java.util.List;

public record QaGoldAnnotationRequest(
        String schemaVersion,
        String datasetVersion,
        int candidatePoolSize,
        List<CaseRequest> cases
) {
    public QaGoldAnnotationRequest {
        schemaVersion = text(schemaVersion);
        datasetVersion = text(datasetVersion);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record CaseRequest(
            String id,
            String workspaceId,
            String query,
            List<String> allowedSourceIds,
            int topK
    ) {
        public CaseRequest {
            id = text(id);
            workspaceId = text(workspaceId);
            query = text(query);
            allowedSourceIds = allowedSourceIds == null ? List.of() : allowedSourceIds.stream()
                    .map(QaGoldAnnotationRequest::text)
                    .distinct()
                    .toList();
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
