package com.noteweave.artifact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateArtifactJobRequest(
        @NotBlank @Size(max = 64) String actionKey,
        @Size(max = 64) String styleProfileKey,
        @Size(max = 128) String contextSnapshotId
) {
}
