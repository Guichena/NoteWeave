package com.noteweave.artifact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record CreateArtifactJobRequest(
        @NotBlank @Size(max = 64) String skillKey,
        @NotBlank @Size(max = 4000) String userRequirement,
        Map<String, Object> inputs,
        @Size(max = 50) List<@NotBlank @Size(max = 36) String> sourceScopeSourceIds,
        @Size(max = 20) List<ArtifactUpstreamRefRequest> upstreamRefs
) {
    public CreateArtifactJobRequest {
        sourceScopeSourceIds = sourceScopeSourceIds == null
                ? List.of()
                : sourceScopeSourceIds.stream().map(String::trim).distinct().toList();
        upstreamRefs = upstreamRefs == null ? List.of() : List.copyOf(upstreamRefs);
    }
}
