package com.noteweave.team.rag.evidence;

import lombok.Builder;

@Builder
public record EvidenceOptions(
        int maxEvidencePerDocument,
        boolean mergeAdjacentChunks,
        int maxMergedChars,
        int finalTopK,
        int maxContextChars
) {
}
