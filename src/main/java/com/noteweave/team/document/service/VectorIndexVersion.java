package com.noteweave.team.document.service;

import lombok.Builder;

@Builder
public record VectorIndexVersion(
        String aliasName,
        String indexName,
        String model,
        int dimension
) {
}
