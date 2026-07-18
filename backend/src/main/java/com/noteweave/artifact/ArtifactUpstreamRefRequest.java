package com.noteweave.artifact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ArtifactUpstreamRefRequest(
        @NotBlank @Size(max = 64) String refType,
        @NotBlank @Size(max = 128) String refId,
        @NotBlank @Size(max = 128) String revisionId
) {
    public ArtifactUpstreamRefRequest {
        refType = refType == null ? null : refType.trim().toUpperCase(java.util.Locale.ROOT);
        refId = refId == null ? null : refId.trim();
        revisionId = revisionId == null ? null : revisionId.trim();
    }
}
