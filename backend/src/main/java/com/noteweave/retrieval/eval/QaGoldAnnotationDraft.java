package com.noteweave.retrieval.eval;

import java.time.Instant;
import java.util.List;

public record QaGoldAnnotationDraft(
        String schemaVersion,
        String datasetVersion,
        int candidatePoolSize,
        Instant capturedAt,
        List<DraftCase> cases
) {
    public QaGoldAnnotationDraft {
        schemaVersion = text(schemaVersion);
        datasetVersion = text(datasetVersion);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record DraftCase(
            String id,
            String mode,
            String workspaceId,
            String query,
            List<String> allowedSourceIds,
            int topK,
            long latencyMicros,
            int candidateCount,
            String rawInputFingerprint,
            String annotationStatus,
            Boolean shouldRefuse,
            List<String> relevantEvidenceIds,
            List<String> expectedCitationIds,
            String reviewerNotes,
            List<DraftCandidate> candidates
    ) {
        public DraftCase {
            id = text(id);
            mode = text(mode);
            workspaceId = text(workspaceId);
            query = text(query);
            allowedSourceIds = copy(allowedSourceIds);
            rawInputFingerprint = text(rawInputFingerprint);
            annotationStatus = text(annotationStatus);
            relevantEvidenceIds = copy(relevantEvidenceIds);
            expectedCitationIds = copy(expectedCitationIds);
            reviewerNotes = text(reviewerNotes);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record DraftCandidate(
            int rank,
            String evidenceId,
            String sourceId,
            String sourceSnapshotId,
            String chunkNo,
            String title,
            String content,
            String sourceType,
            double score,
            boolean withinAllowedScope,
            List<String> citationIds
    ) {
        public DraftCandidate {
            evidenceId = text(evidenceId);
            sourceId = text(sourceId);
            sourceSnapshotId = text(sourceSnapshotId);
            chunkNo = text(chunkNo);
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
