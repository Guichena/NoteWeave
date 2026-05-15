package com.noteweave.team.rag.retriever;

import java.util.List;

public record TeamRetrievalQuery(
        Long userId,
        Long spaceId,
        List<Long> knowledgeBaseIds,
        String query,
        int topK
) {
}
