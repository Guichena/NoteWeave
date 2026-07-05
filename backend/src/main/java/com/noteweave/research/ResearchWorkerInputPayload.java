package com.noteweave.research;

public record ResearchWorkerInputPayload(
        String question,
        String profileKey,
        String contextSnapshotId
) {
}
