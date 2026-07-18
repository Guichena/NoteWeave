package com.noteweave.artifact;

import jakarta.validation.constraints.Size;

public record RollbackArtifactVersionRequest(
        @Size(max = 300) String title
) {
}
