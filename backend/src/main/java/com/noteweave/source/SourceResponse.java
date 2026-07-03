package com.noteweave.source;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record SourceResponse(
        @JsonProperty("source_id") String sourceId,
        String title,
        @JsonProperty("source_type") String sourceType,
        String status,
        @JsonProperty("parse_status") String parseStatus,
        @JsonProperty("index_status") String indexStatus,
        @JsonProperty("updated_at") Instant updatedAt
) {
}
