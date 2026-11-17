package com.noteweave.artifact;

import jakarta.validation.constraints.Size;
import java.util.Map;

public record RegenerateArtifactVersionRequest(
        @Size(max = 20000) String userRequirement,
        Map<String, Object> inputs,
        @Size(min = 1, max = 120) @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._:-]+") String clientRequestId
) {
    public RegenerateArtifactVersionRequest(String userRequirement, Map<String, Object> inputs) {
        this(userRequirement, inputs, null);
    }
}
