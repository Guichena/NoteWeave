package com.noteweave.search.dto;

import com.noteweave.team.rag.retriever.RetrievalMode;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SearchDebugResponse {
    private List<SearchHitResponse> items;
    private RetrievalMode retrievalMode;
    private SearchDebugMeta debug;

    @Getter
    @Builder
    public static class SearchDebugMeta {
        private int bm25Count;
        private int vectorCount;
        private int fusionCount;
    }
}
