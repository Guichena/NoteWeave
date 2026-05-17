package com.noteweave.team.rag.retriever;

import java.util.Map;
import lombok.Builder;

@Builder
public record RrfOptions(
        int rrfK,
        int topK,
        Map<String, Double> weights
) {
}
