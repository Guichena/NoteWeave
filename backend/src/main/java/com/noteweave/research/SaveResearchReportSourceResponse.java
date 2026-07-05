package com.noteweave.research;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SaveResearchReportSourceResponse(
        @JsonProperty("source_id") String sourceId,
        String title,
        @JsonProperty("source_type") String sourceType,
        String status,
        @JsonProperty("parse_status") String parseStatus,
        @JsonProperty("index_status") String indexStatus,
        @JsonProperty("generated_by") String generatedBy,
        @JsonProperty("generated_ref_id") String generatedRefId
) {
}
