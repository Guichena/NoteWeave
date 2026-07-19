package com.noteweave.research;

import java.time.Instant;
import java.util.List;

public record ResearchCollectionResponse(
        String collectionId,
        String workspaceId,
        String researchRunId,
        String title,
        String status,
        Report report,
        List<AdoptedSource> adoptedSources,
        List<Note> notes,
        Instant createdAt,
        Instant updatedAt
) {
    public record Report(String title, String markdown) { }

    public record AdoptedSource(
            String sourceKind,
            String evidenceId,
            String sourceId,
            String sourceSnapshotKey,
            String sourceUrl,
            String sourceDomain,
            String title,
            String excerpt,
            int citationCount
    ) { }

    public record Note(
            String noteKey,
            String noteType,
            String title,
            String content,
            String evidenceRefsJson
    ) { }
}
