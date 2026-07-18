package com.noteweave.artifact;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ArtifactSavedSourceResponse(
        @JsonProperty("source_id") String sourceId,
        @JsonProperty("artifact_job_id") String artifactJobId,
        @JsonProperty("artifact_version_id") String artifactVersionId,
        String status,
        @JsonProperty("parse_status") String parseStatus,
        @JsonProperty("index_status") String indexStatus,
        @JsonProperty("generated_by") String generatedBy,
        @JsonProperty("generated_ref_id") String generatedRefId
) {
}
