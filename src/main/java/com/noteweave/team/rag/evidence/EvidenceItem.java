package com.noteweave.team.rag.evidence;

import java.util.List;

public record EvidenceItem(
        int citationIndex,
        Long documentId,
        String documentTitle,
        Integer indexVersion,
        Integer chunkIndex,
        String content,
        Double score,
        List<EvidenceSource> sources
) {
}
