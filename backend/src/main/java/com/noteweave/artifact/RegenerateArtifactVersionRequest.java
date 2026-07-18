package com.noteweave.artifact;

import jakarta.validation.constraints.Size;
import java.util.Map;

public record RegenerateArtifactVersionRequest(
        @Size(max = 20000) String userRequirement,
        Map<String, Object> inputs
) {
}
